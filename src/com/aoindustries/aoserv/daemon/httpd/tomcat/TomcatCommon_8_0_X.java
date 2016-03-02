/*
 * Copyright 2016 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.UpgradeSymlink;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.IOException;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Set;

/**
 * Some common code for Tomcat 8.0.X
 *
 * @author  AO Industries, Inc.
 */
class TomcatCommon_8_0_X extends TomcatCommon {

	private static final TomcatCommon_8_0_X instance = new TomcatCommon_8_0_X();
	static TomcatCommon_8_0_X getInstance() {
		return instance;
	}

	private TomcatCommon_8_0_X() {}

	@Override
	protected Set<PackageManager.PackageName> getRequiredPackages() {
		return EnumSet.of(PackageManager.PackageName.APACHE_TOMCAT_8_0);
	}

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
				+ "            maxActive=\"").print(dataSource.getMaxActive()).print("\"\n"
				+ "            maxIdle=\"").print(dataSource.getMaxIdle()).print("\"\n"
				+ "            maxWait=\"").print(dataSource.getMaxWait()).print("\"\n");
		if(dataSource.getValidationQuery()!=null) {
			out.print("            validationQuery=\"").encodeXmlAttribute(dataSource.getValidationQuery()).print("\"\n");
		}
		out.print("            removeAbandoned=\"true\"\n"
				+ "            removeAbandonedTimeout=\"300\"\n"
				+ "            logAbandoned=\"true\"\n"
				+ "          />\n");
	}

	/**
	 * Upgrade to Tomcat 8.0.30.
	 */
	private static final UpgradeSymlink[] upgradeSymlinks_8_0_30 = {
		// Nothing to do
	};

	/**
	 * Upgrade to Tomcat 8.0.32.
	 */
	private static final UpgradeSymlink[] upgradeSymlinks_8_0_32 = {
		// Upgrade from Tomcat 8.0.30 to 8.0.32
		new UpgradeSymlink(
			"lib/postgresql-9.4.1207.jar",
			"../../../opt/apache-tomcat-8.0/lib/postgresql-9.4.1207.jar",
			null
		),
		new UpgradeSymlink(
			"lib/postgresql-9.4.1208.jar",
			null,
			"../../../opt/apache-tomcat-8.0/lib/postgresql-9.4.1208.jar"
		)
	};

	/**
	 * Upgrades the Tomcat 8.0.X installed in the provided directory.
	 */
	boolean upgradeTomcatDirectory(UnixFile tomcatDirectory, int uid, int gid) throws IOException, SQLException {
		boolean needsRestart = false;
		OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		if(osConfig==OperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {
			String rpmVersion = PackageManager.getInstalledPackage(PackageManager.PackageName.APACHE_TOMCAT_8_0).getVersion().toString();
			if(rpmVersion.equals("8.0.30")) {
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_8_0_30) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else if(rpmVersion.equals("8.0.32")) {
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_8_0_32) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else {
				throw new IllegalStateException("Unexpected version of Tomcat: " + rpmVersion);
			}
		}
		return needsRestart;
	}
}
