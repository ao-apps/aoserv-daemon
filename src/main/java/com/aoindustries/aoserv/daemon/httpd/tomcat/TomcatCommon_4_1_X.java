/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2008-2013, 2015, 2017, 2018, 2020, 2021  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoindustries.aoserv.client.web.tomcat.ContextDataSource;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
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
	boolean upgradeTomcatDirectory(String optSlash, PosixFile tomcatDirectory, int uid, int gid) throws IOException, SQLException {
		boolean needsRestart = false;
		// Nothing to do
		return needsRestart;
	}

	@Override
	protected String getApacheTomcatDir() {
		return "apache-tomcat-4.1";
	}
}
