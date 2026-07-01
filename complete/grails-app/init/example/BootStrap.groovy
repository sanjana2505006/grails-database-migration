package example

class BootStrap {

    def init = { servletContext ->
        environments {
            development {
                Person.withTransaction {
                    if (Person.count() == 0) {
                        def ada = new Person(name: 'Ada Lovelace', age: 36).save(failOnError: true, flush: true)
                        new Address(
                            person: ada,
                            streetName: '10 Downing Street',
                            city: 'London',
                            zipCode: 'SW1A 2AA'
                        ).save(failOnError: true, flush: true)

                        new Person(name: 'Grace Hopper', age: null).save(failOnError: true, flush: true)
                    }
                }
            }
        }
    }

    def destroy = {
    }
}
