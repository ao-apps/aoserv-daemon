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
 * along with aoserv-daemon.  If not, see <http://www.gnu.org/licenses/>.
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
 * Some common code for Tomcat 5.5.X
 *
 * @author  AO Industries, Inc.
 */
class TomcatCommon_5_5_X extends TomcatCommon {

	private static final TomcatCommon_5_5_X instance = new TomcatCommon_5_5_X();
	static TomcatCommon_5_5_X getInstance() {
		return instance;
	}

	private TomcatCommon_5_5_X() {}

	@Override
	protected Set<PackageManager.PackageName> getRequiredPackages() throws IOException, SQLException {
		return EnumSet.of(
			OperatingSystemConfiguration.getOperatingSystemConfiguration().getJdk17PackageName(),
			PackageManager.PackageName.APACHE_TOMCAT_5_5
		);
	}

	@Override
	public void writeHttpdTomcatDataSource(ContextDataSource dataSource, ChainWriter out) throws SQLException, IOException {
		out.print("          <Resource\n"
				+ "            name=\"").textInXmlAttribute(dataSource.getName()).print("\"\n"
				+ "            auth=\"Container\"\n"
				+ "            type=\"javax.sql.DataSource\"\n");
		String username = dataSource.getUsername();
		if(username != null && !username.isEmpty()) {
			out.print("            username=\"").textInXmlAttribute(username).print("\"\n");
		}
		String password = dataSource.getPassword();
		if(password != null && !password.isEmpty()) {
			out.print("            password=\"").textInXmlAttribute(password).print("\"\n");
		}
		String driverClassName = dataSource.getDriverClassName();
		if(driverClassName != null && !driverClassName.isEmpty()) {
			out.print("            driverClassName=\"").textInXmlAttribute(driverClassName).print("\"\n");
		}
		out.print("            url=\"").textInXmlAttribute(dataSource.getUrl()).print("\"\n"
				+ "            maxActive=\"").textInXmlAttribute(dataSource.getMaxActive()).print("\"\n"
				+ "            maxIdle=\"").textInXmlAttribute(dataSource.getMaxIdle()).print("\"\n"
				+ "            maxWait=\"").textInXmlAttribute(dataSource.getMaxWait()).print("\"\n");
		if(dataSource.getValidationQuery()!=null) {
			out.print("            validationQuery=\"").textInXmlAttribute(dataSource.getValidationQuery()).print("\"\n");
		}
		out.print("            removeAbandoned=\"true\"\n"
				+ "            removeAbandonedTimeout=\"300\"\n"
				+ "            logAbandoned=\"true\"\n"
				+ "          />\n");
	}

	/**
	 * Upgrades the Tomcat 5.5.X installed in the provided directory.
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
		return "apache-tomcat-5.5";
	}
}
