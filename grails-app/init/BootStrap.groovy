import grails.util.Environment
import net.zomis.duga.Authority
import net.zomis.duga.User
import net.zomis.duga.UserAuthority

class BootStrap {

    def tasks

    def init = { servletContext ->
        def users = User.list()
        if (users.isEmpty()) {
            def roleUser = new Authority(authority: 'ROLE_USER').save(failOnError: true)
            def roleAdmin = new Authority(authority: 'ROLE_ADMIN').save(failOnError: true)
            def user = new User(username: 'user', password: 'user', enabled: true, accountExpired: false, accountLocked: false, credentialsExpired: false ).save(failOnError: true)
            String adminPassword = 'admin' + Math.random()
            if (Environment.current == Environment.DEVELOPMENT) {
                adminPassword = 'admin'
            }
            def admin = new User(username: 'admin', password: adminPassword, enabled: true, accountExpired: false, accountLocked: false, credentialsExpired: false ).save(failOnError: true)
            UserAuthority.create(user, roleUser, true)
            UserAuthority.create(admin, roleUser, true)
            UserAuthority.create(admin, roleAdmin, true)
        }
        tasks.initOnce()
    }
    def destroy = {
    }
}
