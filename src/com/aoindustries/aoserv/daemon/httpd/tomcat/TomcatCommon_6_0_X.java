/*
 * Copyright 2008-2013, 2015, 2016 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.daemon.AOServDaemon;
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
 * Some common code for Tomcat 6.0.X
 *
 * @author  AO Industries, Inc.
 */
class TomcatCommon_6_0_X extends TomcatCommon {

    private static final TomcatCommon_6_0_X instance = new TomcatCommon_6_0_X();
    static TomcatCommon_6_0_X getInstance() {
        return instance;
    }

    private TomcatCommon_6_0_X() {}

	@Override
	protected Set<PackageManager.PackageName> getRequiredPackages() {
		return EnumSet.of(PackageManager.PackageName.APACHE_TOMCAT_6_0);
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
	 * Upgrade to Tomcat 6.0.37.
	 */
	private static final UpgradeSymlink[] upgradeSymlinks_6_0_37 = {
		// Nothing to do
	};

	/**
	 * Upgrade to Tomcat 6.0.45.
	 */
	private static final UpgradeSymlink[] upgradeSymlinks_6_0_45 = {
		// Upgrade from Tomcat 6.0.37 to 6.0.45
		new UpgradeSymlink(
			"lib/ecj-4.2.2.jar",
			"../../../opt/apache-tomcat-6.0/lib/ecj-4.2.2.jar",
			null
		),
		new UpgradeSymlink(
			"lib/ecj-4.3.1.jar",
			null,
			"../../../opt/apache-tomcat-6.0/lib/ecj-4.3.1.jar"
		),
		new UpgradeSymlink(
			"lib/mysql-connector-java-5.1.25-bin.jar",
			"../../../opt/apache-tomcat-6.0/lib/mysql-connector-java-5.1.25-bin.jar",
			null
		),
		new UpgradeSymlink(
			"lib/mysql-connector-java-5.1.38-bin.jar",
			null,
			"../../../opt/apache-tomcat-6.0/lib/mysql-connector-java-5.1.38-bin.jar"
		),
		new UpgradeSymlink(
			"lib/postgresql-9.2-1003.jdbc4.jar",
			"../../../opt/apache-tomcat-6.0/lib/postgresql-9.2-1003.jdbc4.jar",
			null
		),
		new UpgradeSymlink(
			"lib/postgresql-9.4.1208.jre6.jar",
			null,
			"../../../opt/apache-tomcat-6.0/lib/postgresql-9.4.1208.jre6.jar"
		)
	};

	/**
	 * Upgrades the Tomcat 6.0.X installed in the provided directory.
	 */
	boolean upgradeTomcatDirectory(UnixFile tomcatDirectory, int uid, int gid) throws IOException, SQLException {
		boolean needsRestart = false;
		OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
		if(osConfig==OperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {
			String rpmVersion = PackageManager.getInstalledPackage(PackageManager.PackageName.APACHE_TOMCAT_6_0).getVersion().toString();
			if(rpmVersion.equals("6.0.37")) {
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_6_0_37) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
			} else if(rpmVersion.equals("6.0.45")) {
				for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_6_0_45) {
					if(upgradeSymlink.upgradeLinkTarget(tomcatDirectory, uid, gid)) needsRestart = true;
				}
				// Switch from Java 1.7 to Java 1.* now that compatible with Java 1.8
				String results = AOServDaemon.execAndCapture(
					new String[] {
						osConfig.getReplaceCommand(),
						"/opt/jdk1.7-i686/setenv.sh",
						"/opt/jdk1-i686/setenv.sh",
						"--",
						tomcatDirectory.getPath()+"/bin/profile"
					}
				);
				if(results.length()>0) needsRestart = true;
			} else {
				throw new IllegalStateException("Unexpected version of Tomcat: " + rpmVersion);
			}
		}
		return needsRestart;
	}
}
