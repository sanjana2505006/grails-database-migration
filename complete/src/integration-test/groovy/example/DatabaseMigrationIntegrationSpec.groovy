package example

import grails.gorm.transactions.Rollback
import grails.testing.mixin.integration.Integration
import groovy.sql.Sql
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.DirectoryResourceAccessor
import org.springframework.beans.factory.annotation.Autowired
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import spock.lang.Shared
import spock.lang.Specification

import javax.sql.DataSource
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager

@Integration
class DatabaseMigrationIntegrationSpec extends Specification {

    @Shared
    static PostgreSQLContainer postgres = new PostgreSQLContainer<>(DockerImageName.parse('postgres:16-alpine'))
            .withDatabaseName('migrate_path')
            .withUsername('test')
            .withPassword('test')

    @Autowired
    DataSource dataSource

    def setupSpec() {
        postgres.start()
    }

    def cleanupSpec() {
        postgres.stop()
    }

    void 'Liquibase tracking tables exist on a clean database'() {
        expect:
        tableExists('databasechangelog')
        tableExists('databasechangeloglock')
    }

    void 'person table exists with expected columns and no address columns'() {
        expect:
        tableExists('person')
        columnNames('person').containsAll(['id', 'version', 'name', 'age'])
        !columnNames('person').contains('street_name')
        !columnNames('person').contains('city')
        !columnNames('person').contains('zip_code')
    }

    void 'address table exists with person_id foreign key'() {
        expect:
        tableExists('address')
        columnNames('address').containsAll(['id', 'version', 'person_id', 'street_name', 'city', 'zip_code'])
        foreignKeyExists('address', 'person_id', 'person', 'id')
    }

    @Rollback
    void 'GORM can save Person and Address after migrations apply'() {
        when:
        def person = Person.withTransaction {
            def p = new Person(name: 'Test Person', age: 30).save(flush: true, failOnError: true)
            new Address(
                person: p,
                streetName: 'Main St',
                city: 'Austin',
                zipCode: '78701'
            ).save(flush: true, failOnError: true)
            p
        }

        then:
        person.id
        Address.countByPerson(person) == 1
        Address.findByPerson(person).city == 'Austin'
    }

    void 'legacy person address columns migrate into address table'() {
        given:
        Path migrationsDir = Paths.get('grails-app', 'migrations').toAbsolutePath()
        Connection conn = DriverManager.getConnection(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password
        )
        Sql sql = new Sql(conn)

        when: 'apply migrations through add-address-fields-to-person'
        runLiquibase(conn, migrationsDir, 'changelog-through-address-fields.groovy')

        and: 'seed a legacy person row with denormalized address columns'
        sql.executeInsert('''insert into person (version, name, age, street_name, city, zip_code)
                            values (0, 'Legacy Person', 42, 'Congress Ave', 'Austin', '78701')''')

        and: 'apply the redesign + data migration changesets'
        runLiquibase(conn, migrationsDir, 'create-address-table.groovy')

        then: 'address row matches the legacy values and person address columns are gone'
        def address = sql.firstRow('select street_name, city, zip_code from address')
        address.street_name == 'Congress Ave'
        address.city == 'Austin'
        address.zip_code == '78701'
        !columnNames(conn, 'person').contains('street_name')
        !columnNames(conn, 'person').contains('city')
        !columnNames(conn, 'person').contains('zip_code')

        cleanup:
        sql?.close()
        conn?.close()
    }

    private static void runLiquibase(Connection conn, Path migrationsDir, String changelog) {
        def database = DatabaseFactory.instance.findCorrectDatabaseImplementation(new JdbcConnection(conn))
        Liquibase liquibase = new Liquibase(
                changelog,
                new DirectoryResourceAccessor(migrationsDir),
                database
        )
        liquibase.update('')
    }

    private boolean tableExists(String table) {
        def conn = dataSource.connection
        try {
            return tableExists(conn, table)
        } finally {
            conn.close()
        }
    }

    private static boolean tableExists(Connection conn, String table) {
        def rs = conn.metaData.getTables(null, 'public', table, ['TABLE'] as String[])
        return rs.next()
    }

    private Set<String> columnNames(String table) {
        def conn = dataSource.connection
        try {
            return columnNames(conn, table)
        } finally {
            conn.close()
        }
    }

    private static Set<String> columnNames(Connection conn, String table) {
        def rs = conn.metaData.getColumns(null, 'public', table, null)
        def names = [] as Set
        while (rs.next()) {
            names << rs.getString('COLUMN_NAME').toLowerCase()
        }
        return names
    }

    private boolean foreignKeyExists(String fkTable, String fkColumn, String pkTable, String pkColumn) {
        def conn = dataSource.connection
        try {
            def rs = conn.metaData.getImportedKeys(null, 'public', fkTable)
            while (rs.next()) {
                if (rs.getString('FKTABLE_NAME').equalsIgnoreCase(fkTable) &&
                    rs.getString('FKCOLUMN_NAME').equalsIgnoreCase(fkColumn) &&
                    rs.getString('PKTABLE_NAME').equalsIgnoreCase(pkTable) &&
                    rs.getString('PKCOLUMN_NAME').equalsIgnoreCase(pkColumn)) {
                    return true
                }
            }
            return false
        } finally {
            conn.close()
        }
    }
}
