rm -rf target/release
mkdir target/release
cd target/release
git clone git@github.com:grails-plugins/grails-spring-security-kerberos.git
cd grails-spring-security-kerberos
grails clean
grails compile

#grails publish-plugin --snapshot --stacktrace
grails publish-plugin --stacktrace
