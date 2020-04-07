/*
 * Copyright 2008-2013, 2015, 2017, 2018, 2020 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.web.tomcat.ContextDataSource;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.encoding.ChainWriter;
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
	protected Set<PackageManager.PackageName> getRequiredPackages() throws IOException, SQLException {
		return EnumSet.of(
			OperatingSystemConfiguration.getOperatingSystemConfiguration().getJdk17PackageName(),
			PackageManager.PackageName.APACHE_TOMCAT_4_1
		);
	}

	@Override
	public void writeHttpdTomcatDataSource(ContextDataSource dataSource, ChainWriter out) throws IOException, SQLException {
		out.print("          <Resource\n"
				+ "            name=\"").textInXmlAttribute(dataSource.getName()).print("\"\n"
				+ "            auth=\"Container\"\n"
				+ "            type=\"javax.sql.DataSource\"\n"
				+ "          />\n"
				+ "          <ResourceParams name=\"").textInXmlAttribute(dataSource.getName()).print("\">\n"
				+ "            <parameter><name>factory</name><value>org.apache.commons.dbcp.BasicDataSourceFactory</value></parameter>\n"
				+ "            <parameter><name>username</name><value>").textInXhtml(dataSource.getUsername()).print("</value></parameter>\n"
				+ "            <parameter><name>password</name><value>").textInXhtml(dataSource.getPassword()).print("</value></parameter>\n"
				+ "            <parameter><name>driverClassName</name><value>").textInXhtml(dataSource.getDriverClassName()).print("</value></parameter>\n"
				+ "            <parameter><name>url</name><value>").textInXhtml(dataSource.getUrl()).print("</value></parameter>\n"
				+ "            <parameter><name>maxActive</name><value>").textInXhtml(dataSource.getMaxActive()).print("</value></parameter>\n"
				+ "            <parameter><name>maxIdle</name><value>").textInXhtml(dataSource.getMaxIdle()).print("</value></parameter>\n"
				+ "            <parameter><name>maxWait</name><value>").textInXhtml(dataSource.getMaxWait()).print("</value></parameter>\n");
		if(dataSource.getValidationQuery()!=null) {
			out.print("            <parameter><name>validationQuery</name><value>").textInXhtml(dataSource.getValidationQuery()).print("</value></parameter>\n");
		}
		out.print("            <parameter><name>removeAbandoned</name><value>true</value></parameter>\n"
				+ "            <parameter><name>removeAbandonedTimeout</name><value>300</value></parameter>\n"
				+ "            <parameter><name>logAbandoned</name><value>true</value></parameter>\n"
				+ "          </ResourceParams>\n");
	}

	/**
	 * Upgrades the Tomcat 4.1.X installed in the provided directory.
	 *
	 * @param optSlash  Relative path from the CATALINA_HOME to /opt/, including trailing slash, such as <code>../../opt/</code>.
	 */
	boolean upgradeTomcatDirectory(String optSlash, UnixFile tomcatDirectory, int uid, int gid) throws IOException, SQLException {
		boolean needsRestart = false;
		// Nothing to do
		return needsRestart;
	}

	@Override
	protected String getApacheTomcatDir() {
		return "apache-tomcat-4.1";
	}
}
