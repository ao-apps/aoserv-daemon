package com.aoindustries.aoserv.daemon.httpd.tomcat;


/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.util.UpgradeSymlink;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Some common code for Tomcat 4.1.X
 *
 * @author  AO Industries, Inc.
 */
class TomcatCommon_4_1_X extends TomcatCommon {

    private static final TomcatCommon_4_1_X instance = new TomcatCommon_4_1_X();
    static TomcatCommon_4_1_X getInstance() {
        return instance;
    }

    private TomcatCommon_4_1_X() {}

    public void writeHttpdTomcatDataSource(HttpdTomcatDataSource dataSource, ChainWriter out) throws SQLException {
        out.print("          <Resource\n"
                + "            name=\"").printXmlAttribute(dataSource.getName()).print("\"\n"
                + "            auth=\"Container\"\n"
                + "            type=\"javax.sql.DataSource\"\n"
                + "          />\n"
                + "          <ResourceParams name=\"").printXmlAttribute(dataSource.getName()).print("\">\n"
                + "            <parameter><name>factory</name><value>org.apache.commons.dbcp.BasicDataSourceFactory</value></parameter>\n"
                + "            <parameter><name>username</name><value>").printXmlBody(dataSource.getUsername()).print("</value></parameter>\n"
                + "            <parameter><name>password</name><value>").printXmlBody(dataSource.getPassword()).print("</value></parameter>\n"
                + "            <parameter><name>driverClassName</name><value>").printXmlBody(dataSource.getDriverClassName()).print("</value></parameter>\n"
                + "            <parameter><name>url</name><value>").printXmlBody(dataSource.getUrl()).print("</value></parameter>\n"
                + "            <parameter><name>maxActive</name><value>").print(dataSource.getMaxActive()).print("</value></parameter>\n"
                + "            <parameter><name>maxIdle</name><value>").print(dataSource.getMaxIdle()).print("</value></parameter>\n"
                + "            <parameter><name>maxWait</name><value>").print(dataSource.getMaxWait()).print("</value></parameter>\n");
        if(dataSource.getValidationQuery()!=null) {
            out.print("            <parameter><name>validationQuery</name><value>").printXmlBody(dataSource.getValidationQuery()).print("</value></parameter>\n");
        }
        out.print("            <parameter><name>removeAbandoned</name><value>true</value></parameter>\n"
                + "            <parameter><name>removeAbandonedTimeout</name><value>300</value></parameter>\n"
                + "            <parameter><name>logAbandoned</name><value>true</value></parameter>\n"
                + "          </ResourceParams>\n");
    }

    public String getDefaultJdkVersion() {
        return "1.5.0";
    }

    /**
     * kwrite Regexp:
     * ^(.*) -> ../../../usr/apache/jakarta/tomcat/4.1/(.*)$
     *         new UpgradeSymlink("\1", "../../../usr/apache/jakarta/tomcat/4.1/\2", "../../../opt/apache-tomcat-4.1/\2"),
     * 
     * ^(.*) -> ../../../../usr/apache/jakarta/tomcat/4.1/(.*)$
     *         new UpgradeSymlink("\1", "../../../../usr/apache/jakarta/tomcat/4.1/\2", "../../../../opt/apache-tomcat-4.1/\2"),
     */
    private static final UpgradeSymlink[] upgradeSymlinks_4_1_Newest = {
        // Upgrade from Mandriva 2006.0 to CentOS 5
        new UpgradeSymlink("bin/bootstrap.jar", "../../../usr/apache/jakarta/tomcat/4.1/bin/bootstrap.jar", "../../../opt/apache-tomcat-4.1/bin/bootstrap.jar"),
        new UpgradeSymlink("bin/commons-daemon.jar", "../../../usr/apache/jakarta/tomcat/4.1/bin/commons-daemon.jar", "../../../opt/apache-tomcat-4.1/bin/commons-daemon.jar"),
        new UpgradeSymlink("bin/digest.sh", "../../../usr/apache/jakarta/tomcat/4.1/bin/digest.sh", "../../../opt/apache-tomcat-4.1/bin/digest.sh"),
        new UpgradeSymlink("bin/jasper.sh", "../../../usr/apache/jakarta/tomcat/4.1/bin/jasper.sh", "../../../opt/apache-tomcat-4.1/bin/jasper.sh"),
        new UpgradeSymlink("bin/jspc.sh", "../../../usr/apache/jakarta/tomcat/4.1/bin/jspc.sh", "../../../opt/apache-tomcat-4.1/bin/jspc.sh"),
        new UpgradeSymlink("bin/setclasspath.sh", "../../../usr/apache/jakarta/tomcat/4.1/bin/setclasspath.sh", "../../../opt/apache-tomcat-4.1/bin/setclasspath.sh"),
        new UpgradeSymlink("bin/tomcat-jni.jar", "../../../usr/apache/jakarta/tomcat/4.1/bin/tomcat-jni.jar", "../../../opt/apache-tomcat-4.1/bin/tomcat-jni.jar"),
        new UpgradeSymlink("bin/tool-wrapper.sh", "../../../usr/apache/jakarta/tomcat/4.1/bin/tool-wrapper.sh", "../../../opt/apache-tomcat-4.1/bin/tool-wrapper.sh"),
        new UpgradeSymlink("common/endorsed/xercesImpl.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/endorsed/xercesImpl.jar", "../../../../opt/apache-tomcat-4.1/common/endorsed/xercesImpl.jar"),
        new UpgradeSymlink("common/endorsed/xml-apis.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/endorsed/xml-apis.jar", "../../../../opt/apache-tomcat-4.1/common/endorsed/xml-apis.jar"),
        new UpgradeSymlink("common/lib/activation.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/activation.jar", "../../../../opt/apache-tomcat-4.1/common/lib/activation.jar"),
        new UpgradeSymlink("common/lib/ant.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/ant.jar", "../../../../opt/apache-tomcat-4.1/common/lib/ant.jar"),
        new UpgradeSymlink("common/lib/ant-launcher.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/ant-launcher.jar", "../../../../opt/apache-tomcat-4.1/common/lib/ant-launcher.jar"),
        new UpgradeSymlink("common/lib/commons-collections-3.2.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/commons-collections-3.2.jar", "../../../../opt/apache-tomcat-4.1/common/lib/commons-collections-3.2.jar"),
        new UpgradeSymlink("common/lib/commons-dbcp-1.2.1.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/commons-dbcp-1.2.1.jar", "../../../../opt/apache-tomcat-4.1/common/lib/commons-dbcp-1.2.1.jar"),
        new UpgradeSymlink("common/lib/commons-logging-api-1.1.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/commons-logging-api-1.1.jar", "../../../../opt/apache-tomcat-4.1/common/lib/commons-logging-api-1.1.jar"),
        new UpgradeSymlink("common/lib/commons-pool-1.3.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/commons-pool-1.3.jar", "../../../../opt/apache-tomcat-4.1/common/lib/commons-pool-1.3.jar"),
        new UpgradeSymlink("common/lib/jasper-compiler.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/jasper-compiler.jar", "../../../../opt/apache-tomcat-4.1/common/lib/jasper-compiler.jar"),
        new UpgradeSymlink("common/lib/jasper-runtime.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/jasper-runtime.jar", "../../../../opt/apache-tomcat-4.1/common/lib/jasper-runtime.jar"),
        new UpgradeSymlink("common/lib/jdbc2_0-stdext.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/jdbc2_0-stdext.jar", "../../../../opt/apache-tomcat-4.1/common/lib/jdbc2_0-stdext.jar"),
        new UpgradeSymlink("common/lib/jndi.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/jndi.jar", "../../../../opt/apache-tomcat-4.1/common/lib/jndi.jar"),
        new UpgradeSymlink("common/lib/jta.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/jta.jar", "../../../../opt/apache-tomcat-4.1/common/lib/jta.jar"),
        new UpgradeSymlink("common/lib/mail.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/mail.jar", "../../../../opt/apache-tomcat-4.1/common/lib/mail.jar"),
        new UpgradeSymlink("common/lib/mysql-connector-java-3.0.11-stable-bin.jar", "../../../../usr/mysql-connector-java-3.0.11-stable/mysql-connector-java-3.0.11-stable-bin.jar", "../../../../opt/mysql-connector-java-3.0.11-stable/mysql-connector-java-3.0.11-stable-bin.jar"),
        new UpgradeSymlink("common/lib/naming-common.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/naming-common.jar", "../../../../opt/apache-tomcat-4.1/common/lib/naming-common.jar"),
        new UpgradeSymlink("common/lib/naming-factory.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/naming-factory.jar", "../../../../opt/apache-tomcat-4.1/common/lib/naming-factory.jar"),
        new UpgradeSymlink("common/lib/naming-resources.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/naming-resources.jar", "../../../../opt/apache-tomcat-4.1/common/lib/naming-resources.jar"),
        new UpgradeSymlink("common/lib/postgresql.jar", "../../../../usr/postgresql-7.3/share/java/postgresql.jar", "../../../../opt/postgresql-7.3/share/java/postgresql.jar"),
        new UpgradeSymlink("common/lib/servlet.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/servlet.jar", "../../../../opt/apache-tomcat-4.1/common/lib/servlet.jar"),
        new UpgradeSymlink("server/lib/catalina-ant.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/catalina-ant.jar", "../../../../opt/apache-tomcat-4.1/server/lib/catalina-ant.jar"),
        new UpgradeSymlink("server/lib/catalina.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/catalina.jar", "../../../../opt/apache-tomcat-4.1/server/lib/catalina.jar"),
        new UpgradeSymlink("server/lib/commons-beanutils.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/commons-beanutils.jar", "../../../../opt/apache-tomcat-4.1/server/lib/commons-beanutils.jar"),
        new UpgradeSymlink("server/lib/commons-digester-1.7.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/commons-digester-1.7.jar", "../../../../opt/apache-tomcat-4.1/server/lib/commons-digester-1.7.jar"),
        new UpgradeSymlink("server/lib/commons-fileupload-1.1.1.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/commons-fileupload-1.1.1.jar", "../../../../opt/apache-tomcat-4.1/server/lib/commons-fileupload-1.1.1.jar"),
        new UpgradeSymlink("server/lib/commons-io-1.2.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/commons-io-1.2.jar", "../../../../opt/apache-tomcat-4.1/server/lib/commons-io-1.2.jar"),
        new UpgradeSymlink("server/lib/commons-logging-1.1.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/commons-logging-1.1.jar", "../../../../opt/apache-tomcat-4.1/server/lib/commons-logging-1.1.jar"),
        new UpgradeSymlink("server/lib/commons-modeler.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/commons-modeler.jar", "../../../../opt/apache-tomcat-4.1/server/lib/commons-modeler.jar"),
        new UpgradeSymlink("server/lib/jaas.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/jaas.jar", "../../../../opt/apache-tomcat-4.1/server/lib/jaas.jar"),
        new UpgradeSymlink("server/lib/jakarta-regexp-1.4.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/jakarta-regexp-1.4.jar", "../../../../opt/apache-tomcat-4.1/server/lib/jakarta-regexp-1.4.jar"),
        new UpgradeSymlink("server/lib/mx4j-jmx.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/mx4j-jmx.jar", "../../../../opt/apache-tomcat-4.1/server/lib/mx4j-jmx.jar"),
        new UpgradeSymlink("server/lib/mx4j.license", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/mx4j.license", "../../../../opt/apache-tomcat-4.1/server/lib/mx4j.license"),
        new UpgradeSymlink("server/lib/servlets-cgi.renametojar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/servlets-cgi.renametojar", "../../../../opt/apache-tomcat-4.1/server/lib/servlets-cgi.renametojar"),
        new UpgradeSymlink("server/lib/servlets-common.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/servlets-common.jar", "../../../../opt/apache-tomcat-4.1/server/lib/servlets-common.jar"),
        new UpgradeSymlink("server/lib/servlets-default.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/servlets-default.jar", "../../../../opt/apache-tomcat-4.1/server/lib/servlets-default.jar"),
        new UpgradeSymlink("server/lib/servlets-invoker.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/servlets-invoker.jar", "../../../../opt/apache-tomcat-4.1/server/lib/servlets-invoker.jar"),
        new UpgradeSymlink("server/lib/servlets-manager.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/servlets-manager.jar", "../../../../opt/apache-tomcat-4.1/server/lib/servlets-manager.jar"),
        new UpgradeSymlink("server/lib/servlets-ssi.renametojar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/servlets-ssi.renametojar", "../../../../opt/apache-tomcat-4.1/server/lib/servlets-ssi.renametojar"),
        new UpgradeSymlink("server/lib/servlets-webdav.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/servlets-webdav.jar", "../../../../opt/apache-tomcat-4.1/server/lib/servlets-webdav.jar"),
        new UpgradeSymlink("server/lib/tomcat4-coyote.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/tomcat4-coyote.jar", "../../../../opt/apache-tomcat-4.1/server/lib/tomcat4-coyote.jar"),
        new UpgradeSymlink("server/lib/tomcat-coyote.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/tomcat-coyote.jar", "../../../../opt/apache-tomcat-4.1/server/lib/tomcat-coyote.jar"),
        new UpgradeSymlink("server/lib/tomcat-http11.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/tomcat-http11.jar", "../../../../opt/apache-tomcat-4.1/server/lib/tomcat-http11.jar"),
        new UpgradeSymlink("server/lib/tomcat-jk2.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/tomcat-jk2.jar", "../../../../opt/apache-tomcat-4.1/server/lib/tomcat-jk2.jar"),
        new UpgradeSymlink("server/lib/tomcat-jk.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/tomcat-jk.jar", "../../../../opt/apache-tomcat-4.1/server/lib/tomcat-jk.jar"),
        new UpgradeSymlink("server/lib/tomcat-util.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/tomcat-util.jar", "../../../../opt/apache-tomcat-4.1/server/lib/tomcat-util.jar")
    };

    /**
     * Upgrades the Tomcat 4.1.X installed in the provided directory.
     */
    boolean upgradeTomcatDirectory(UnixFile tomcatDirectory, int uid, int gid) throws IOException, SQLException {
        boolean needsRestart = false;
        OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
        if(osConfig==OperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {
            // Tomcat 5.5.Latest
            for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_4_1_Newest) {
                if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
            }
            // Replace /usr/aoserv/etc in bin/profile
            String results = AOServDaemon.execAndCapture(
                new String[] {
                    osConfig.getReplaceCommand(),
                    "/usr/aoserv/etc/",
                    "/opt/aoserv-client/scripts/",
                    "/aoserv.sh",
                    "/aoserv-client.sh",
                    "--",
                    tomcatDirectory.getPath()+"/bin/profile"
                }
            );
            if(results.length()>0) needsRestart = true;
        }
        return needsRestart;
    }
}
