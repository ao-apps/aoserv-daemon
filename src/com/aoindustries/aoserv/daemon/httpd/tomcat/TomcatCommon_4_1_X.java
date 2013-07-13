/*
 * Copyright 2008-2013 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.UpgradeSymlink;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.IOException;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Set;

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

	@Override
	protected Set<PackageManager.PackageName> getRequiredPackages() {
		return EnumSet.of(PackageManager.PackageName.APACHE_TOMCAT_4_1);
	}

	@Override
	public void writeHttpdTomcatDataSource(HttpdTomcatDataSource dataSource, ChainWriter out) throws IOException, SQLException {
		out.print("          <Resource\n"
				+ "            name=\"").encodeXmlAttribute(dataSource.getName()).print("\"\n"
				+ "            auth=\"Container\"\n"
				+ "            type=\"javax.sql.DataSource\"\n"
				+ "          />\n"
				+ "          <ResourceParams name=\"").encodeXmlAttribute(dataSource.getName()).print("\">\n"
				+ "            <parameter><name>factory</name><value>org.apache.commons.dbcp.BasicDataSourceFactory</value></parameter>\n"
				+ "            <parameter><name>username</name><value>").encodeXhtml(dataSource.getUsername()).print("</value></parameter>\n"
				+ "            <parameter><name>password</name><value>").encodeXhtml(dataSource.getPassword()).print("</value></parameter>\n"
				+ "            <parameter><name>driverClassName</name><value>").encodeXhtml(dataSource.getDriverClassName()).print("</value></parameter>\n"
				+ "            <parameter><name>url</name><value>").encodeXhtml(dataSource.getUrl()).print("</value></parameter>\n"
				+ "            <parameter><name>maxActive</name><value>").print(dataSource.getMaxActive()).print("</value></parameter>\n"
				+ "            <parameter><name>maxIdle</name><value>").print(dataSource.getMaxIdle()).print("</value></parameter>\n"
				+ "            <parameter><name>maxWait</name><value>").print(dataSource.getMaxWait()).print("</value></parameter>\n");
		if(dataSource.getValidationQuery()!=null) {
			out.print("            <parameter><name>validationQuery</name><value>").encodeXhtml(dataSource.getValidationQuery()).print("</value></parameter>\n");
		}
		out.print("            <parameter><name>removeAbandoned</name><value>true</value></parameter>\n"
				+ "            <parameter><name>removeAbandonedTimeout</name><value>300</value></parameter>\n"
				+ "            <parameter><name>logAbandoned</name><value>true</value></parameter>\n"
				+ "          </ResourceParams>\n");
	}

	private static final UpgradeSymlink[] upgradeSymlinks_4_1 = {
		// Upgrade from Tomcat 4.1.31
		new UpgradeSymlink(
			"bin/bootstrap.jar",
			"../../../opt/apache-tomcat-4.1.31/bin/bootstrap.jar",
			"../../../opt/apache-tomcat-4.1/bin/bootstrap.jar"
		),
		new UpgradeSymlink(
			"bin/commons-daemon.jar",
			"../../../opt/apache-tomcat-4.1.31/bin/commons-daemon.jar",
			"../../../opt/apache-tomcat-4.1/bin/commons-daemon.jar"
		),
		new UpgradeSymlink(
			"bin/digest.sh",
			"../../../opt/apache-tomcat-4.1.31/bin/digest.sh",
			"../../../opt/apache-tomcat-4.1/bin/digest.sh"
		),
		new UpgradeSymlink(
			"bin/jasper.sh",
			"../../../opt/apache-tomcat-4.1.31/bin/jasper.sh",
			"../../../opt/apache-tomcat-4.1/bin/jasper.sh"
		),
		new UpgradeSymlink(
			"bin/jspc.sh",
			"../../../opt/apache-tomcat-4.1.31/bin/jspc.sh",
			"../../../opt/apache-tomcat-4.1/bin/jspc.sh"
		),
		new UpgradeSymlink(
			"bin/setclasspath.sh",
			"../../../opt/apache-tomcat-4.1.31/bin/setclasspath.sh",
			"../../../opt/apache-tomcat-4.1/bin/setclasspath.sh"
		),
		new UpgradeSymlink(
			"bin/tomcat-jni.jar",
			"../../../opt/apache-tomcat-4.1.31/bin/tomcat-jni.jar",
			"../../../opt/apache-tomcat-4.1/bin/tomcat-jni.jar"
		),
		new UpgradeSymlink(
			"bin/tool-wrapper.sh",
			"../../../opt/apache-tomcat-4.1.31/bin/tool-wrapper.sh",
			"../../../opt/apache-tomcat-4.1/bin/tool-wrapper.sh"
		),
		new UpgradeSymlink(
			"common/endorsed/xercesImpl.jar",
			"../../../../opt/apache-tomcat-4.1.31/common/endorsed/xercesImpl.jar",
			"../../../../opt/apache-tomcat-4.1/common/endorsed/xercesImpl.jar"
		),
		new UpgradeSymlink(
			"common/endorsed/xmlParserAPIs.jar",
			"../../../../opt/apache-tomcat-4.1.31/common/endorsed/xmlParserAPIs.jar",
			null
		),
		new UpgradeSymlink(
			"common/lib/activation.jar",
			"../../../../opt/apache-tomcat-4.1.31/common/lib/activation.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/activation.jar"
		),
		new UpgradeSymlink(
			"common/lib/ant.jar",
			"../../../../opt/apache-tomcat-4.1.31/common/lib/ant.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/ant.jar"
		),
		new UpgradeSymlink(
			"common/lib/commons-collections.jar",
			"../../../../opt/apache-tomcat-4.1.31/common/lib/commons-collections.jar",
			null
		),
		new UpgradeSymlink(
			"common/lib/commons-dbcp-1.1.jar",
			"../../../../opt/apache-tomcat-4.1.31/common/lib/commons-dbcp-1.1.jar",
			null
		),
		new UpgradeSymlink(
			"common/lib/commons-logging-api.jar",
			"../../../../opt/apache-tomcat-4.1.31/common/lib/commons-logging-api.jar",
			null
		),
		new UpgradeSymlink(
			"common/lib/commons-pool-1.1.jar",
			"../../../../opt/apache-tomcat-4.1.31/common/lib/commons-pool-1.1.jar",
			null
		),
		new UpgradeSymlink(
			"common/lib/jasper-compiler.jar",
			"../../../../opt/apache-tomcat-4.1.31/common/lib/jasper-compiler.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/jasper-compiler.jar"
		),
		new UpgradeSymlink(
			"common/lib/jasper-runtime.jar",
			"../../../../opt/apache-tomcat-4.1.31/common/lib/jasper-runtime.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/jasper-runtime.jar"
		),
		new UpgradeSymlink(
			"common/lib/jdbc2_0-stdext.jar",
			"../../../../opt/apache-tomcat-4.1.31/common/lib/jdbc2_0-stdext.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/jdbc2_0-stdext.jar"
		),
		new UpgradeSymlink(
			"common/lib/jndi.jar",
			"../../../../opt/apache-tomcat-4.1.31/common/lib/jndi.jar",
			null
		),
		new UpgradeSymlink(
			"common/lib/jta.jar",
			"../../../../opt/apache-tomcat-4.1.31/common/lib/jta.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/jta.jar"
		),
		new UpgradeSymlink(
			"common/lib/mail.jar",
			"../../../../opt/apache-tomcat-4.1.31/common/lib/mail.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/mail.jar"
		),
		new UpgradeSymlink(
			"common/lib/naming-common.jar",
			"../../../../opt/apache-tomcat-4.1.31/common/lib/naming-common.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/naming-common.jar"
		),
		new UpgradeSymlink(
			"common/lib/naming-factory.jar",
			"../../../../opt/apache-tomcat-4.1.31/common/lib/naming-factory.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/naming-factory.jar"
		),
		new UpgradeSymlink(
			"common/lib/naming-resources.jar",
			"../../../../opt/apache-tomcat-4.1.31/common/lib/naming-resources.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/naming-resources.jar"
		),
		new UpgradeSymlink(
			"common/lib/servlet.jar",
			"../../../../opt/apache-tomcat-4.1.31/common/lib/servlet.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/servlet.jar"
		),
		new UpgradeSymlink(
			"server/lib/catalina-ant.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/catalina-ant.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/catalina-ant.jar"
		),
		new UpgradeSymlink(
			"server/lib/catalina.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/catalina.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/catalina.jar"
		),
		new UpgradeSymlink(
			"server/lib/commons-beanutils.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/commons-beanutils.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/commons-beanutils.jar"
		),
		new UpgradeSymlink(
			"server/lib/commons-digester.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/commons-digester.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/commons-fileupload-1.0.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/commons-fileupload-1.0.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/commons-logging.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/commons-logging.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/commons-modeler.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/commons-modeler.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/jaas.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/jaas.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/jakarta-regexp-1.3.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/jakarta-regexp-1.3.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/mx4j-jmx.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/mx4j-jmx.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/servlets-cgi.renametojar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/servlets-cgi.renametojar",
			"../../../../opt/apache-tomcat-4.1/server/lib/servlets-cgi.renametojar"
		),
		new UpgradeSymlink(
			"server/lib/servlets-common.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/servlets-common.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/servlets-common.jar"
		),
		new UpgradeSymlink(
			"server/lib/servlets-default.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/servlets-default.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/servlets-default.jar"
		),
		new UpgradeSymlink(
			"server/lib/servlets-invoker.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/servlets-invoker.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/servlets-invoker.jar"
		),
		new UpgradeSymlink(
			"server/lib/servlets-manager.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/servlets-manager.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/servlets-manager.jar"
		),
		new UpgradeSymlink(
			"server/lib/servlets-ssi.renametojar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/servlets-ssi.renametojar",
			"../../../../opt/apache-tomcat-4.1/server/lib/servlets-ssi.renametojar"
		),
		new UpgradeSymlink(
			"server/lib/servlets-webdav.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/servlets-webdav.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/servlets-webdav.jar"
		),
		new UpgradeSymlink(
			"server/lib/tomcat-coyote.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/tomcat-coyote.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/tomcat-coyote.jar"
		),
		new UpgradeSymlink(
			"server/lib/tomcat-http11.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/tomcat-http11.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/tomcat-http11.jar"
		),
		new UpgradeSymlink(
			"server/lib/tomcat-jk2.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/tomcat-jk2.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/tomcat-jk2.jar"
		),
		new UpgradeSymlink(
			"server/lib/tomcat-jk.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/tomcat-jk.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/tomcat-jk.jar"
		),
		new UpgradeSymlink(
			"server/lib/tomcat-util.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/tomcat-util.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/tomcat-util.jar"
		),
		new UpgradeSymlink(
			"server/lib/tomcat-warp.jar",
			"../../../../opt/apache-tomcat-4.1.31/server/lib/tomcat-warp.jar",
			null
		),
		// Upgrade from Tomcat 4.1.34
		new UpgradeSymlink(
			"bin/bootstrap.jar",
			"../../../opt/apache-tomcat-4.1.34/bin/bootstrap.jar",
			"../../../opt/apache-tomcat-4.1/bin/bootstrap.jar"
		),
		new UpgradeSymlink(
			"bin/catalina.sh",
			"../../../opt/apache-tomcat-4.1.34/bin/catalina.sh",
			"../../../opt/apache-tomcat-4.1/bin/catalina.sh"
		),
		new UpgradeSymlink(
			"bin/commons-daemon.jar",
			"../../../opt/apache-tomcat-4.1.34/bin/commons-daemon.jar",
			"../../../opt/apache-tomcat-4.1/bin/commons-daemon.jar"
		),
		new UpgradeSymlink(
			"bin/digest.sh",
			"../../../opt/apache-tomcat-4.1.34/bin/digest.sh",
			"../../../opt/apache-tomcat-4.1/bin/digest.sh"
		),
		new UpgradeSymlink(
			"bin/jasper.sh",
			"../../../opt/apache-tomcat-4.1.34/bin/jasper.sh",
			"../../../opt/apache-tomcat-4.1/bin/jasper.sh"
		),
		new UpgradeSymlink(
			"bin/jspc.sh",
			"../../../opt/apache-tomcat-4.1.34/bin/jspc.sh",
			"../../../opt/apache-tomcat-4.1/bin/jspc.sh"
		),
		new UpgradeSymlink(
			"bin/setclasspath.sh",
			"../../../opt/apache-tomcat-4.1.34/bin/setclasspath.sh",
			"../../../opt/apache-tomcat-4.1/bin/setclasspath.sh"
		),
		new UpgradeSymlink(
			"bin/tomcat-jni.jar",
			"../../../opt/apache-tomcat-4.1.34/bin/tomcat-jni.jar",
			"../../../opt/apache-tomcat-4.1/bin/tomcat-jni.jar"
		),
		new UpgradeSymlink(
			"bin/tool-wrapper.sh",
			"../../../opt/apache-tomcat-4.1.34/bin/tool-wrapper.sh",
			"../../../opt/apache-tomcat-4.1/bin/tool-wrapper.sh"
		),
		new UpgradeSymlink(
			"common/endorsed/xercesImpl.jar",
			"../../../../opt/apache-tomcat-4.1.34/common/endorsed/xercesImpl.jar",
			"../../../../opt/apache-tomcat-4.1/common/endorsed/xercesImpl.jar"
		),
		new UpgradeSymlink(
			"common/endorsed/xml-apis.jar",
			"../../../../opt/apache-tomcat-4.1.34/common/endorsed/xml-apis.jar",
			"../../../../opt/apache-tomcat-4.1/common/endorsed/xml-apis.jar"
		),
		new UpgradeSymlink(
			"common/lib/activation.jar",
			"../../../../opt/apache-tomcat-4.1.34/common/lib/activation.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/activation.jar"
		),
		new UpgradeSymlink(
			"common/lib/ant.jar",
			"../../../../opt/apache-tomcat-4.1.34/common/lib/ant.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/ant.jar"
		),
		new UpgradeSymlink(
			"common/lib/ant-launcher.jar",
			"../../../../opt/apache-tomcat-4.1.34/common/lib/ant-launcher.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/ant-launcher.jar"
		),
		new UpgradeSymlink(
			"common/lib/commons-collections-3.2.jar",
			"../../../../opt/apache-tomcat-4.1.34/common/lib/commons-collections-3.2.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/commons-collections-3.2.jar"
		),
		new UpgradeSymlink(
			"common/lib/commons-dbcp-1.2.1.jar",
			"../../../../opt/apache-tomcat-4.1.34/common/lib/commons-dbcp-1.2.1.jar",
			null
		),
		new UpgradeSymlink(
			"common/lib/commons-logging-api-1.1.jar",
			"../../../../opt/apache-tomcat-4.1.34/common/lib/commons-logging-api-1.1.jar",
			null
		),
		new UpgradeSymlink(
			"common/lib/commons-pool-1.3.jar",
			"../../../../opt/apache-tomcat-4.1.34/common/lib/commons-pool-1.3.jar",
			null
		),
		new UpgradeSymlink(
			"common/lib/jasper-compiler.jar",
			"../../../../opt/apache-tomcat-4.1.34/common/lib/jasper-compiler.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/jasper-compiler.jar"
		),
		new UpgradeSymlink(
			"common/lib/jasper-runtime.jar",
			"../../../../opt/apache-tomcat-4.1.34/common/lib/jasper-runtime.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/jasper-runtime.jar"
		),
		new UpgradeSymlink(
			"common/lib/jdbc2_0-stdext.jar",
			"../../../../opt/apache-tomcat-4.1.34/common/lib/jdbc2_0-stdext.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/jdbc2_0-stdext.jar"
		),
		new UpgradeSymlink(
			"common/lib/jndi.jar",
			"../../../../opt/apache-tomcat-4.1.34/common/lib/jndi.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/jndi.jar"
		),
		new UpgradeSymlink(
			"common/lib/jta.jar",
			"../../../../opt/apache-tomcat-4.1.34/common/lib/jta.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/jta.jar"
		),
		new UpgradeSymlink(
			"common/lib/mail.jar",
			"../../../../opt/apache-tomcat-4.1.34/common/lib/mail.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/mail.jar"
		),
		new UpgradeSymlink(
			"common/lib/naming-common.jar",
			"../../../../opt/apache-tomcat-4.1.34/common/lib/naming-common.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/naming-common.jar"
		),
		new UpgradeSymlink(
			"common/lib/naming-factory.jar",
			"../../../../opt/apache-tomcat-4.1.34/common/lib/naming-factory.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/naming-factory.jar"
		),
		new UpgradeSymlink(
			"common/lib/naming-resources.jar",
			"../../../../opt/apache-tomcat-4.1.34/common/lib/naming-resources.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/naming-resources.jar"
		),
		new UpgradeSymlink(
			"common/lib/servlet.jar",
			"../../../../opt/apache-tomcat-4.1.34/common/lib/servlet.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/servlet.jar"
		),
		new UpgradeSymlink(
			"server/lib/catalina-ant.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/catalina-ant.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/catalina-ant.jar"
		),
		new UpgradeSymlink(
			"server/lib/catalina.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/catalina.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/catalina.jar"
		),
		new UpgradeSymlink(
			"server/lib/commons-beanutils.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/commons-beanutils.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/commons-beanutils.jar"
		),
		new UpgradeSymlink(
			"server/lib/commons-digester-1.7.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/commons-digester-1.7.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/commons-fileupload-1.1.1.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/commons-fileupload-1.1.1.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/commons-io-1.2.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/commons-io-1.2.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/commons-logging-1.1.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/commons-logging-1.1.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/commons-modeler.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/commons-modeler.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/jaas.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/jaas.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/jaas.jar"
		),
		new UpgradeSymlink(
			"server/lib/jakarta-regexp-1.4.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/jakarta-regexp-1.4.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/jakarta-regexp-1.4.jar"
		),
		new UpgradeSymlink(
			"server/lib/mx4j-jmx.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/mx4j-jmx.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/mx4j.license",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/mx4j.license",
			"../../../../opt/apache-tomcat-4.1/server/lib/mx4j.license"
		),
		new UpgradeSymlink(
			"server/lib/servlets-cgi.renametojar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/servlets-cgi.renametojar",
			"../../../../opt/apache-tomcat-4.1/server/lib/servlets-cgi.renametojar"
		),
		new UpgradeSymlink(
			"server/lib/servlets-common.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/servlets-common.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/servlets-common.jar"
		),
		new UpgradeSymlink(
			"server/lib/servlets-default.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/servlets-default.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/servlets-default.jar"
		),
		new UpgradeSymlink(
			"server/lib/servlets-invoker.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/servlets-invoker.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/servlets-invoker.jar"
		),
		new UpgradeSymlink(
			"server/lib/servlets-manager.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/servlets-manager.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/servlets-manager.jar"
		),
		new UpgradeSymlink(
			"server/lib/servlets-ssi.renametojar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/servlets-ssi.renametojar",
			"../../../../opt/apache-tomcat-4.1/server/lib/servlets-ssi.renametojar"
		),
		new UpgradeSymlink(
			"server/lib/servlets-webdav.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/servlets-webdav.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/servlets-webdav.jar"
		),
		new UpgradeSymlink(
			"server/lib/tomcat4-coyote.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/tomcat4-coyote.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/tomcat4-coyote.jar"
		),
		new UpgradeSymlink(
			"server/lib/tomcat-coyote.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/tomcat-coyote.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/tomcat-coyote.jar"
		),
		new UpgradeSymlink(
			"server/lib/tomcat-http11.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/tomcat-http11.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/tomcat-http11.jar"
		),
		new UpgradeSymlink(
			"server/lib/tomcat-jk2.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/tomcat-jk2.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/tomcat-jk2.jar"
		),
		new UpgradeSymlink(
			"server/lib/tomcat-jk.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/tomcat-jk.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/tomcat-jk.jar"
		),
		new UpgradeSymlink(
			"server/lib/tomcat-util.jar",
			"../../../../opt/apache-tomcat-4.1.34/server/lib/tomcat-util.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/tomcat-util.jar"
		),
		// Links to remove entirely
		new UpgradeSymlink(
			"common/lib/commons-collections.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/commons-collections.jar",
			null
		),
		new UpgradeSymlink(
			"common/lib/commons-dbcp-1.2.1.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/commons-dbcp-1.2.1.jar",
			null
		),
		new UpgradeSymlink(
			"common/lib/commons-logging-api-1.1.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/commons-logging-api-1.1.jar",
			null
		),
		new UpgradeSymlink(
			"common/lib/commons-pool-1.3.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/commons-pool-1.3.jar",
			null
		),
		new UpgradeSymlink(
			"common/lib/jndi.jar",
			"../../../../opt/apache-tomcat-4.1/common/lib/jndi.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/commons-digester-1.7.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/commons-digester-1.7.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/commons-fileupload-1.1.1.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/commons-fileupload-1.1.1.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/commons-io-1.2.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/commons-io-1.2.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/commons-logging-1.1.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/commons-logging-1.1.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/commons-modeler.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/commons-modeler.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/jaas.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/jaas.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/jakarta-regexp-1.4.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/jakarta-regexp-1.4.jar",
			null
		),
		new UpgradeSymlink(
			"server/lib/mx4j-jmx.jar",
			"../../../../opt/apache-tomcat-4.1/server/lib/mx4j-jmx.jar",
			null
		),
		// New links for current Tomcat version
		new UpgradeSymlink(
			"common/endorsed/xml-apis.jar",
			null,
			"../../../../opt/apache-tomcat-4.1/common/endorsed/xml-apis.jar"
		),
		new UpgradeSymlink(
			"common/lib/commons-collections-3.2.jar",
			null,
			"../../../../opt/apache-tomcat-4.1/common/lib/commons-collections-3.2.jar"
		),
		new UpgradeSymlink(
			"common/lib/commons-dbcp-1.2.2.jar",
			null,
			"../../../../opt/apache-tomcat-4.1/common/lib/commons-dbcp-1.2.2.jar"
		),
		new UpgradeSymlink(
			"common/lib/commons-logging-api-1.1.1.jar",
			null,
			"../../../../opt/apache-tomcat-4.1/common/lib/commons-logging-api-1.1.1.jar"
		),
		new UpgradeSymlink(
			"common/lib/commons-pool-1.4.jar",
			null,
			"../../../../opt/apache-tomcat-4.1/common/lib/commons-pool-1.4.jar"
		),
		new UpgradeSymlink(
			"server/lib/commons-digester-1.8.jar",
			null,
			"../../../../opt/apache-tomcat-4.1/server/lib/commons-digester-1.8.jar"
		),
		new UpgradeSymlink(
			"server/lib/commons-fileupload-1.2.jar",
			null,
			"../../../../opt/apache-tomcat-4.1/server/lib/commons-fileupload-1.2.jar"
		),
		new UpgradeSymlink(
			"server/lib/commons-io-1.3.jar",
			null,
			"../../../../opt/apache-tomcat-4.1/server/lib/commons-io-1.3.jar"
		),
		new UpgradeSymlink(
			"server/lib/commons-logging-1.1.1.jar",
			null,
			"../../../../opt/apache-tomcat-4.1/server/lib/commons-logging-1.1.1.jar"
		),
		new UpgradeSymlink(
			"server/lib/commons-modeler-2.0.1.jar",
			null,
			"../../../../opt/apache-tomcat-4.1/server/lib/commons-modeler-2.0.1.jar"
		),
		new UpgradeSymlink(
			"server/lib/jakarta-regexp-1.5.jar",
			null,
			"../../../../opt/apache-tomcat-4.1/server/lib/jakarta-regexp-1.5.jar"
		),
		new UpgradeSymlink(
			"server/lib/mx4j.jar",
			null,
			"../../../../opt/apache-tomcat-4.1/server/lib/mx4j.jar"
		)
	};

	/**
	 * Upgrades the Tomcat 4.1.X installed in the provided directory.
	 */
	boolean upgradeTomcatDirectory(UnixFile tomcatDirectory, int uid, int gid) throws IOException, SQLException {
		boolean needsRestart = false;
		OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		if(osConfig==OperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {
			// Tomcat 4.1
			for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_4_1) {
				if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
			}
			// MySQL
			//for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_MySQL) {
			//	if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
			//}
			// PostgreSQL
			//for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_PostgreSQL) {
			//	if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
			//}
			// Update bin/profile
			// TODO
			/*
			String results = AOServDaemon.execAndCapture(
				new String[] {
					osConfig.getReplaceCommand(),
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
					"--",
					tomcatDirectory.getPath()+"/bin/profile"
				}
			);
			if(results.length()>0) needsRestart = true;
			 */
		}
		return needsRestart;
	}
}
