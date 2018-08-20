/*
 * Copyright 2018 by AO Industries, Inc.,
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
 * Some common code for Tomcat 8.5.X
 *
 * @author  AO Industries, Inc.
 */
class TomcatCommon_8_5_X extends TomcatCommon {

	private static final TomcatCommon_8_5_X instance = new TomcatCommon_8_5_X();
	static TomcatCommon_8_5_X getInstance() {
		return instance;
	}

	private TomcatCommon_8_5_X() {}

	@Override
	protected Set<PackageManager.PackageName> getRequiredPackages() throws IOException, SQLException {
		return EnumSet.of(
			OperatingSystemConfiguration.getOperatingSystemConfiguration().getDefaultJdkPackageName(),
			PackageManager.PackageName.AOSERV_PROFILE_D,
			PackageManager.PackageName.APACHE_TOMCAT_8_5
		);
	}

	/**
	 * See:
	 * <ol>
	 * <li><a href="https://tomcat.apache.org/tomcat-8.5-doc/jndi-datasource-examples-howto.html">JNDI Datasource HOW-TO</a></li>
	 * <li><a href="https://tomcat.apache.org/tomcat-8.5-doc/jdbc-pool.html">The Tomcat JDBC Connection Pool</a></li>
	 * </ol>
	 */
	@Override 
	public void writeHttpdTomcatDataSource(HttpdTomcatDataSource dataSource, ChainWriter out) throws SQLException, IOException {
		out.print("          <Resource\n"
				+ "            name=\"").encodeXmlAttribute(dataSource.getName()).print("\"\n"
				+ "            auth=\"Container\"\n"
				+ "            type=\"javax.sql.DataSource\"\n"
				+ "            username=\"").encodeXmlAttribute(dataSource.getUsername()).print("\"\n"
				+ "            password=\"").encodeXmlAttribute(dataSource.getPassword()).print("\"\n"
				+ "            driverClassName=\"").encodeXmlAttribute(dataSource.getDriverClassName()).print("\"\n"
				+ "            url=\"").encodeXmlAttribute(dataSource.getUrl()).print("\"\n"
				+ "            maxActive=\"").encodeXmlAttribute(dataSource.getMaxActive()).print("\"\n"
				+ "            maxIdle=\"").encodeXmlAttribute(dataSource.getMaxIdle()).print("\"\n"
				+ "            maxWait=\"").encodeXmlAttribute(dataSource.getMaxWait()).print("\"\n");
		if(dataSource.getValidationQuery()!=null) {
			out.print("            testWhileIdle=\"true\"\n"
					+ "            validationQuery=\"").encodeXmlAttribute(dataSource.getValidationQuery()).print("\"\n"
					+ "            validationQueryTimeout=\"30\"\n");
		}
		out.print("            removeAbandoned=\"true\"\n"
				+ "            removeAbandonedTimeout=\"300\"\n"
				+ "            logAbandoned=\"true\"\n"
				+ "          />\n");
	}

	/**
	 * Upgrades the Tomcat 8.5.X installed in the provided directory.
	 *
	 * @param optSlash  Relative path from the CATALINA_HOME to /opt/, including trailing slash, such as <code>../../opt/</code>.
	 */
	boolean upgradeTomcatDirectory(String optSlash, UnixFile tomcatDirectory, int uid, int gid) throws IOException, SQLException {
		// TODO: This might be able to simply use the same lnAll as used to initially create the lib/ directory
		boolean needsRestart = false;
		OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		if(
			osConfig == OperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64
			|| osConfig == OperatingSystemConfiguration.CENTOS_7_X86_64
		) {
			String rpmVersion = PackageManager.getInstalledPackage(PackageManager.PackageName.APACHE_TOMCAT_8_5).getVersion().toString();
			if(rpmVersion.equals("8.5.32")) {
				// Nothing to do
			} else {
				throw new IllegalStateException("Unexpected version of Tomcat: " + rpmVersion);
			}
		}
		return needsRestart;
	}
}
