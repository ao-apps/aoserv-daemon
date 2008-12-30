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
import java.util.ArrayList;
import java.util.List;

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
    private static final List<UpgradeSymlink> upgradeSymlinks_4_1_31 = new ArrayList<UpgradeSymlink>();
    static {
        // Upgrade from Mandriva 2006.0 to CentOS 5
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("bin/bootstrap.jar", "../../../usr/apache/jakarta/tomcat/4.1.31/bin/bootstrap.jar", "../../../opt/apache-tomcat-4.1.31/bin/bootstrap.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("bin/catalina.sh", "../../../usr/apache/jakarta/tomcat/4.1.31/bin/catalina.sh", "../../../opt/apache-tomcat-4.1.31/bin/catalina.sh"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("bin/commons-daemon.jar", "../../../usr/apache/jakarta/tomcat/4.1.31/bin/commons-daemon.jar", "../../../opt/apache-tomcat-4.1.31/bin/commons-daemon.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("bin/digest.sh", "../../../usr/apache/jakarta/tomcat/4.1.31/bin/digest.sh", "../../../opt/apache-tomcat-4.1.31/bin/digest.sh"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("bin/jasper.sh", "../../../usr/apache/jakarta/tomcat/4.1.31/bin/jasper.sh", "../../../opt/apache-tomcat-4.1.31/bin/jasper.sh"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("bin/jspc.sh", "../../../usr/apache/jakarta/tomcat/4.1.31/bin/jspc.sh", "../../../opt/apache-tomcat-4.1.31/bin/jspc.sh"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("bin/setclasspath.sh", "../../../usr/apache/jakarta/tomcat/4.1.31/bin/setclasspath.sh", "../../../opt/apache-tomcat-4.1.31/bin/setclasspath.sh"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("bin/tomcat-jni.jar", "../../../usr/apache/jakarta/tomcat/4.1.31/bin/tomcat-jni.jar", "../../../opt/apache-tomcat-4.1.31/bin/tomcat-jni.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("bin/tool-wrapper.sh", "../../../usr/apache/jakarta/tomcat/4.1.31/bin/tool-wrapper.sh", "../../../opt/apache-tomcat-4.1.31/bin/tool-wrapper.sh"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("common/endorsed/xercesImpl.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/common/endorsed/xercesImpl.jar", "../../../../opt/apache-tomcat-4.1.31/common/endorsed/xercesImpl.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("common/endorsed/xmlParserAPIs.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/common/endorsed/xmlParserAPIs.jar", "../../../../opt/apache-tomcat-4.1.31/common/endorsed/xmlParserAPIs.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("common/lib/activation.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/common/lib/activation.jar", "../../../../opt/apache-tomcat-4.1.31/common/lib/activation.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("common/lib/ant.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/common/lib/ant.jar", "../../../../opt/apache-tomcat-4.1.31/common/lib/ant.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("common/lib/ant-launcher.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/common/lib/ant-launcher.jar", "../../../../opt/apache-tomcat-4.1.31/common/lib/ant-launcher.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("common/lib/commons-collections.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/common/lib/commons-collections.jar", "../../../../opt/apache-tomcat-4.1.31/common/lib/commons-collections.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("common/lib/commons-dbcp-1.1.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/common/lib/commons-dbcp-1.1.jar", "../../../../opt/apache-tomcat-4.1.31/common/lib/commons-dbcp-1.1.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("common/lib/commons-logging-api.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/common/lib/commons-logging-api.jar", "../../../../opt/apache-tomcat-4.1.31/common/lib/commons-logging-api.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("common/lib/commons-pool-1.1.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/common/lib/commons-pool-1.1.jar", "../../../../opt/apache-tomcat-4.1.31/common/lib/commons-pool-1.1.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("common/lib/jasper-compiler.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/common/lib/jasper-compiler.jar", "../../../../opt/apache-tomcat-4.1.31/common/lib/jasper-compiler.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("common/lib/jasper-runtime.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/common/lib/jasper-runtime.jar", "../../../../opt/apache-tomcat-4.1.31/common/lib/jasper-runtime.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("common/lib/jdbc2_0-stdext.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/common/lib/jdbc2_0-stdext.jar", "../../../../opt/apache-tomcat-4.1.31/common/lib/jdbc2_0-stdext.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("common/lib/jndi.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/common/lib/jndi.jar", "../../../../opt/apache-tomcat-4.1.31/common/lib/jndi.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("common/lib/jta.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/common/lib/jta.jar", "../../../../opt/apache-tomcat-4.1.31/common/lib/jta.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("common/lib/mail.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/common/lib/mail.jar", "../../../../opt/apache-tomcat-4.1.31/common/lib/mail.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("common/lib/naming-common.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/common/lib/naming-common.jar", "../../../../opt/apache-tomcat-4.1.31/common/lib/naming-common.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("common/lib/naming-factory.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/common/lib/naming-factory.jar", "../../../../opt/apache-tomcat-4.1.31/common/lib/naming-factory.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("common/lib/naming-resources.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/common/lib/naming-resources.jar", "../../../../opt/apache-tomcat-4.1.31/common/lib/naming-resources.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("common/lib/servlet.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/common/lib/servlet.jar", "../../../../opt/apache-tomcat-4.1.31/common/lib/servlet.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/catalina-ant.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/catalina-ant.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/catalina-ant.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/catalina.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/catalina.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/catalina.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/commons-beanutils.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/commons-beanutils.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/commons-beanutils.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/commons-digester.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/commons-digester.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/commons-digester.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/commons-fileupload-1.0.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/commons-fileupload-1.0.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/commons-fileupload-1.0.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/commons-logging.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/commons-logging.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/commons-logging.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/commons-modeler.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/commons-modeler.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/commons-modeler.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/jaas.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/jaas.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/jaas.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/jakarta-regexp-1.3.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/jakarta-regexp-1.3.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/jakarta-regexp-1.3.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/mx4j-jmx.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/mx4j-jmx.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/mx4j-jmx.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/mx4j.license", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/mx4j.license", "../../../../opt/apache-tomcat-4.1.31/server/lib/mx4j.license"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/servlets-cgi.renametojar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/servlets-cgi.renametojar", "../../../../opt/apache-tomcat-4.1.31/server/lib/servlets-cgi.renametojar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/servlets-common.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/servlets-common.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/servlets-common.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/servlets-default.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/servlets-default.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/servlets-default.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/servlets-invoker.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/servlets-invoker.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/servlets-invoker.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/servlets-manager.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/servlets-manager.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/servlets-manager.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/servlets-ssi.renametojar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/servlets-ssi.renametojar", "../../../../opt/apache-tomcat-4.1.31/server/lib/servlets-ssi.renametojar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/servlets-webdav.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/servlets-webdav.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/servlets-webdav.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/tomcat-coyote.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/tomcat-coyote.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/tomcat-coyote.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/tomcat-http11.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/tomcat-http11.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/tomcat-http11.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/tomcat-jk2.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/tomcat-jk2.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/tomcat-jk2.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/tomcat-jk.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/tomcat-jk.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/tomcat-jk.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/tomcat-util.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/tomcat-util.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/tomcat-util.jar"));
        upgradeSymlinks_4_1_31.add(new UpgradeSymlink("server/lib/tomcat-warp.jar", "../../../../usr/apache/jakarta/tomcat/4.1.31/server/lib/tomcat-warp.jar", "../../../../opt/apache-tomcat-4.1.31/server/lib/tomcat-warp.jar"));
    };

    private static final List<UpgradeSymlink> upgradeSymlinks_4_1_Newest = new ArrayList<UpgradeSymlink>();
    static {
        // Upgrade from Mandriva 2006.0 to CentOS 5
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("bin/bootstrap.jar", "../../../usr/apache/jakarta/tomcat/4.1/bin/bootstrap.jar", "../../../opt/apache-tomcat-4.1/bin/bootstrap.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("bin/commons-daemon.jar", "../../../usr/apache/jakarta/tomcat/4.1/bin/commons-daemon.jar", "../../../opt/apache-tomcat-4.1/bin/commons-daemon.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("bin/digest.sh", "../../../usr/apache/jakarta/tomcat/4.1/bin/digest.sh", "../../../opt/apache-tomcat-4.1/bin/digest.sh"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("bin/jasper.sh", "../../../usr/apache/jakarta/tomcat/4.1/bin/jasper.sh", "../../../opt/apache-tomcat-4.1/bin/jasper.sh"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("bin/jspc.sh", "../../../usr/apache/jakarta/tomcat/4.1/bin/jspc.sh", "../../../opt/apache-tomcat-4.1/bin/jspc.sh"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("bin/setclasspath.sh", "../../../usr/apache/jakarta/tomcat/4.1/bin/setclasspath.sh", "../../../opt/apache-tomcat-4.1/bin/setclasspath.sh"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("bin/tomcat-jni.jar", "../../../usr/apache/jakarta/tomcat/4.1/bin/tomcat-jni.jar", "../../../opt/apache-tomcat-4.1/bin/tomcat-jni.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("bin/tool-wrapper.sh", "../../../usr/apache/jakarta/tomcat/4.1/bin/tool-wrapper.sh", "../../../opt/apache-tomcat-4.1/bin/tool-wrapper.sh"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("common/endorsed/xercesImpl.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/endorsed/xercesImpl.jar", "../../../../opt/apache-tomcat-4.1/common/endorsed/xercesImpl.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("common/endorsed/xml-apis.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/endorsed/xml-apis.jar", "../../../../opt/apache-tomcat-4.1/common/endorsed/xml-apis.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("common/lib/activation.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/activation.jar", "../../../../opt/apache-tomcat-4.1/common/lib/activation.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("common/lib/ant.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/ant.jar", "../../../../opt/apache-tomcat-4.1/common/lib/ant.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("common/lib/ant-launcher.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/ant-launcher.jar", "../../../../opt/apache-tomcat-4.1/common/lib/ant-launcher.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("common/lib/commons-collections-3.2.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/commons-collections-3.2.jar", "../../../../opt/apache-tomcat-4.1/common/lib/commons-collections-3.2.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("common/lib/commons-dbcp-1.2.1.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/commons-dbcp-1.2.1.jar", "../../../../opt/apache-tomcat-4.1/common/lib/commons-dbcp-1.2.1.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("common/lib/commons-logging-api-1.1.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/commons-logging-api-1.1.jar", "../../../../opt/apache-tomcat-4.1/common/lib/commons-logging-api-1.1.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("common/lib/commons-pool-1.3.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/commons-pool-1.3.jar", "../../../../opt/apache-tomcat-4.1/common/lib/commons-pool-1.3.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("common/lib/jasper-compiler.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/jasper-compiler.jar", "../../../../opt/apache-tomcat-4.1/common/lib/jasper-compiler.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("common/lib/jasper-runtime.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/jasper-runtime.jar", "../../../../opt/apache-tomcat-4.1/common/lib/jasper-runtime.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("common/lib/jdbc2_0-stdext.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/jdbc2_0-stdext.jar", "../../../../opt/apache-tomcat-4.1/common/lib/jdbc2_0-stdext.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("common/lib/jndi.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/jndi.jar", "../../../../opt/apache-tomcat-4.1/common/lib/jndi.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("common/lib/jta.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/jta.jar", "../../../../opt/apache-tomcat-4.1/common/lib/jta.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("common/lib/mail.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/mail.jar", "../../../../opt/apache-tomcat-4.1/common/lib/mail.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("common/lib/naming-common.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/naming-common.jar", "../../../../opt/apache-tomcat-4.1/common/lib/naming-common.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("common/lib/naming-factory.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/naming-factory.jar", "../../../../opt/apache-tomcat-4.1/common/lib/naming-factory.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("common/lib/naming-resources.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/naming-resources.jar", "../../../../opt/apache-tomcat-4.1/common/lib/naming-resources.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("common/lib/servlet.jar", "../../../../usr/apache/jakarta/tomcat/4.1/common/lib/servlet.jar", "../../../../opt/apache-tomcat-4.1/common/lib/servlet.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/catalina-ant.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/catalina-ant.jar", "../../../../opt/apache-tomcat-4.1/server/lib/catalina-ant.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/catalina.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/catalina.jar", "../../../../opt/apache-tomcat-4.1/server/lib/catalina.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/commons-beanutils.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/commons-beanutils.jar", "../../../../opt/apache-tomcat-4.1/server/lib/commons-beanutils.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/commons-digester-1.7.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/commons-digester-1.7.jar", "../../../../opt/apache-tomcat-4.1/server/lib/commons-digester-1.7.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/commons-fileupload-1.1.1.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/commons-fileupload-1.1.1.jar", "../../../../opt/apache-tomcat-4.1/server/lib/commons-fileupload-1.1.1.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/commons-io-1.2.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/commons-io-1.2.jar", "../../../../opt/apache-tomcat-4.1/server/lib/commons-io-1.2.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/commons-logging-1.1.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/commons-logging-1.1.jar", "../../../../opt/apache-tomcat-4.1/server/lib/commons-logging-1.1.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/commons-modeler.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/commons-modeler.jar", "../../../../opt/apache-tomcat-4.1/server/lib/commons-modeler.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/jaas.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/jaas.jar", "../../../../opt/apache-tomcat-4.1/server/lib/jaas.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/jakarta-regexp-1.4.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/jakarta-regexp-1.4.jar", "../../../../opt/apache-tomcat-4.1/server/lib/jakarta-regexp-1.4.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/mx4j-jmx.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/mx4j-jmx.jar", "../../../../opt/apache-tomcat-4.1/server/lib/mx4j-jmx.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/mx4j.license", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/mx4j.license", "../../../../opt/apache-tomcat-4.1/server/lib/mx4j.license"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/servlets-cgi.renametojar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/servlets-cgi.renametojar", "../../../../opt/apache-tomcat-4.1/server/lib/servlets-cgi.renametojar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/servlets-common.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/servlets-common.jar", "../../../../opt/apache-tomcat-4.1/server/lib/servlets-common.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/servlets-default.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/servlets-default.jar", "../../../../opt/apache-tomcat-4.1/server/lib/servlets-default.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/servlets-invoker.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/servlets-invoker.jar", "../../../../opt/apache-tomcat-4.1/server/lib/servlets-invoker.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/servlets-manager.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/servlets-manager.jar", "../../../../opt/apache-tomcat-4.1/server/lib/servlets-manager.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/servlets-ssi.renametojar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/servlets-ssi.renametojar", "../../../../opt/apache-tomcat-4.1/server/lib/servlets-ssi.renametojar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/servlets-webdav.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/servlets-webdav.jar", "../../../../opt/apache-tomcat-4.1/server/lib/servlets-webdav.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/tomcat4-coyote.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/tomcat4-coyote.jar", "../../../../opt/apache-tomcat-4.1/server/lib/tomcat4-coyote.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/tomcat-coyote.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/tomcat-coyote.jar", "../../../../opt/apache-tomcat-4.1/server/lib/tomcat-coyote.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/tomcat-http11.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/tomcat-http11.jar", "../../../../opt/apache-tomcat-4.1/server/lib/tomcat-http11.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/tomcat-jk2.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/tomcat-jk2.jar", "../../../../opt/apache-tomcat-4.1/server/lib/tomcat-jk2.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/tomcat-jk.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/tomcat-jk.jar", "../../../../opt/apache-tomcat-4.1/server/lib/tomcat-jk.jar"));
        upgradeSymlinks_4_1_Newest.add(new UpgradeSymlink("server/lib/tomcat-util.jar", "../../../../usr/apache/jakarta/tomcat/4.1/server/lib/tomcat-util.jar", "../../../../opt/apache-tomcat-4.1/server/lib/tomcat-util.jar"));
    };

    /**
     * Upgrades the Tomcat 4.1.X installed in the provided directory.
     */
    boolean upgradeTomcatDirectory(UnixFile tomcatDirectory, int uid, int gid) throws IOException, SQLException {
        boolean needsRestart = false;
        OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
        if(osConfig==OperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {
            // Tomcat 4.1.31
            for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_4_1_31) {
                if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
            }
            // Tomcat 4.1.Latest
            for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_4_1_Newest) {
                if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
            }
            // MySQL
            for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_MySQL) {
                if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
            }
            // PostgreSQL
            for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_PostgreSQL) {
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
                    "php-4.3.sh",
                    "php-4.sh",
                    "php-4.3.3.sh",
                    "php-4.sh",
                    "php-4.3.8.sh",
                    "php-4.sh",
                    "postgresql-7.3.3.sh",
                    "postgresql-7.3.sh",
                    // Fix previous mistakes
                    "/opt/aoserv-client/scripts/php-4.3.sh",
                    "/opt/aoserv-client/scripts/php-4.sh",
                    "/opt/aoserv-client/scripts/php-4.3.3.sh",
                    "/opt/aoserv-client/scripts/php-4.sh",
                    "/opt/aoserv-client/scripts/php-4.3.8.sh",
                    "/opt/aoserv-client/scripts/php-4.sh",
                    "export \"CLASSPATH=/usr/aoserv/lib-1.3/aocode-public.jar:$CLASSPATH\".d/001_aoserv.sh",
                    "export \"CLASSPATH=/opt/aoserv-client/lib-1.3/aocode-public.jar:$CLASSPATH\"",
                    "--",
                    tomcatDirectory.getPath()+"/bin/profile"
                }
            );
            if(results.length()>0) needsRestart = true;
        }
        return needsRestart;
    }
}
