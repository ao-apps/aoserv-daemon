/*
 * Copyright 2008-2013, 2015, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
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
	public void writeHttpdTomcatDataSource(HttpdTomcatDataSource dataSource, ChainWriter out) throws SQLException, IOException {
		out.print("          <Resource\n"
				+ "            name=\"").encodeXmlAttribute(dataSource.getName()).print("\"\n"
				+ "            auth=\"Container\"\n"
				+ "            type=\"javax.sql.DataSource\"\n");
		String username = dataSource.getUsername();
		if(username != null && !username.isEmpty()) {
			out.print("            username=\"").encodeXmlAttribute(username).print("\"\n");
		}
		String password = dataSource.getPassword();
		if(password != null && !password.isEmpty()) {
			out.print("            password=\"").encodeXmlAttribute(password).print("\"\n");
		}
		String driverClassName = dataSource.getDriverClassName();
		if(driverClassName != null && !driverClassName.isEmpty()) {
			out.print("            driverClassName=\"").encodeXmlAttribute(driverClassName).print("\"\n");
		}
		out.print("            url=\"").encodeXmlAttribute(dataSource.getUrl()).print("\"\n"
				+ "            maxActive=\"").encodeXmlAttribute(dataSource.getMaxActive()).print("\"\n"
				+ "            maxIdle=\"").encodeXmlAttribute(dataSource.getMaxIdle()).print("\"\n"
				+ "            maxWait=\"").encodeXmlAttribute(dataSource.getMaxWait()).print("\"\n");
		if(dataSource.getValidationQuery()!=null) {
			out.print("            validationQuery=\"").encodeXmlAttribute(dataSource.getValidationQuery()).print("\"\n");
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
	boolean upgradeTomcatDirectory(String optSlash, UnixFile tomcatDirectory, int uid, int gid) throws IOException, SQLException {
		boolean needsRestart = false;
		// Nothing to do
		return needsRestart;
	}

	@Override
	protected String getApacheTomcatDir() {
		return "apache-tomcat-5.5";
	}
}
