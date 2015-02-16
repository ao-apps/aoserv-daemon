/*
 * Copyright 2008-2013, 2015 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.client.HttpdTomcatParameter;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.encoding.ChainWriter;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

/**
 * Some common code for all installations of Tomcat.
 *
 * @author  AO Industries, Inc.
 */
public abstract class TomcatCommon {

    TomcatCommon() {
	}

	/**
	 * Gets any packages that must be installed for this site.
	 */
	protected abstract Set<PackageManager.PackageName> getRequiredPackages();

	/**
     * Writes a single parameter.
     */
    public void writeHttpdTomcatParameter(HttpdTomcatParameter parameter, ChainWriter out) throws IOException {
        out.print("          <Parameter\n"
                + "            name=\"").encodeXmlAttribute(parameter.getName()).print("\"\n"
                + "            value=\"").encodeXmlAttribute(parameter.getValue()).print("\"\n"
                + "            override=\"").print(parameter.getOverride()).print("\"\n");
        if(parameter.getDescription()!=null) out.print("            description=\"").encodeXmlAttribute(parameter.getDescription()).print("\"\n");
        out.print("          />\n");
    }

    /**
     * Writes a single data source.
     */
    public abstract void writeHttpdTomcatDataSource(HttpdTomcatDataSource dataSource, ChainWriter out) throws IOException, SQLException;

//    protected static final UpgradeSymlink[] upgradeSymlinks_MySQL = {
//        new UpgradeSymlink("common/lib/mm.mysql-2.0.7-bin.jar", "../../../../usr/mm.mysql-2.0.7/mm.mysql-2.0.7-bin.jar", "../../../../opt/mm.mysql-2.0.7/mm.mysql-2.0.7-bin.jar"),
//        new UpgradeSymlink("common/lib/mm.mysql-2.0.7-bin.jar", "/usr/mm.mysql/2.0.7/mm.mysql-2.0.7-bin.jar", "../../../../opt/mm.mysql-2.0.7/mm.mysql-2.0.7-bin.jar"),
//        new UpgradeSymlink("common/lib/mysql-connector-java-3.0.11-stable-bin.jar", "../../../../usr/mysql-connector-java-3.0.11-stable/mysql-connector-java-3.0.11-stable-bin.jar", "../../../../opt/mysql-connector-java-3.0.11-stable/mysql-connector-java-3.0.11-stable-bin.jar"),
//        new UpgradeSymlink("common/lib/mysql-connector-java-3.0.11-stable-bin.jar", "../../../../usr/mysql-connector-java/3.0.11-stable/mysql-connector-java-3.0.11-stable-bin.jar", "../../../../opt/mysql-connector-java-3.0.11-stable/mysql-connector-java-3.0.11-stable-bin.jar"),
//        new UpgradeSymlink("common/lib/mysql-connector-java-3.1.12-bin.jar", "../../../../usr/mysql-connector-java/3.1.12/mysql-connector-java-3.1.12-bin.jar", "../../../../opt/mysql-connector-java-3.1.12/mysql-connector-java-3.1.12-bin.jar"),
//        new UpgradeSymlink("common/lib/mysql-connector-java-3.1.12-bin.jar", "/usr/mysql-connector-java/3.1.12/mysql-connector-java-3.1.12-bin.jar", "../../../../opt/mysql-connector-java-3.1.12/mysql-connector-java-3.1.12-bin.jar")
//    };

	// TODO: Should change this to somehow determine proper PostgreSQL driver given
	//       The installed PostgreSQL versions and the selected JDK.
	//       Or - just hard-code the most recent???
//    protected static final UpgradeSymlink[] upgradeSymlinks_PostgreSQL = {
//        new UpgradeSymlink(
//            "common/lib/postgresql.jar",
//            "../../../../opt/postgresql-7.1/share/java/postgresql.jar",
//            "../../../../opt/postgresql-7.1-i686/share/java/postgresql.jar"
//        ),
//        new UpgradeSymlink(
//            "server/lib/postgresql.jar",
//            "../../../../opt/postgresql-7.1/share/java/postgresql.jar",
//            "../../../../opt/postgresql-7.1-i686/share/java/postgresql.jar"
//        ),
//        new UpgradeSymlink(
//            "common/lib/postgresql.jar",
//            "../../../../opt/postgresql-7.2/share/java/postgresql.jar",
//            "../../../../opt/postgresql-7.2-i686/share/java/postgresql.jar"
//        ),
//        new UpgradeSymlink(
//            "server/lib/postgresql.jar",
//            "../../../../opt/postgresql-7.2/share/java/postgresql.jar",
//            "../../../../opt/postgresql-7.2-i686/share/java/postgresql.jar"
//        ),
//        new UpgradeSymlink(
//            "common/lib/postgresql.jar",
//            "../../../../opt/postgresql-7.3/share/java/postgresql.jar",
//            "../../../../opt/postgresql-7.3-i686/share/java/postgresql.jar"
//        ),
//        new UpgradeSymlink(
//            "server/lib/postgresql.jar",
//            "../../../../opt/postgresql-7.3/share/java/postgresql.jar",
//            "../../../../opt/postgresql-7.3-i686/share/java/postgresql.jar"
//        ),
//        new UpgradeSymlink(
//            "common/lib/postgresql.jar",
//            "../../../../opt/postgresql-8.1/share/java/postgresql.jar",
//            "../../../../opt/postgresql-8.1-i686/share/java/postgresql.jar"
//        ),
//        new UpgradeSymlink(
//            "server/lib/postgresql.jar",
//            "../../../../opt/postgresql-8.1/share/java/postgresql.jar",
//            "../../../../opt/postgresql-8.1-i686/share/java/postgresql.jar"
//        ),
//        new UpgradeSymlink(
//            "common/lib/postgresql.jar",
//            "../../../../opt/postgresql-8.3/share/java/postgresql.jar",
//            "../../../../opt/postgresql-8.3-i686/share/java/postgresql.jar"
//        ),
//        new UpgradeSymlink(
//            "server/lib/postgresql.jar",
//            "../../../../opt/postgresql-8.3/share/java/postgresql.jar",
//            "../../../../opt/postgresql-8.3-i686/share/java/postgresql.jar"
//        ),
//        new UpgradeSymlink(
//            "lib/postgresql-8.3-605.jdbc3.jar",
//            "../../../opt/postgresql-8.3/share/java/postgresql-8.3-605.jdbc3.jar",
//            "../../../opt/postgresql-8.3-i686/share/java/postgresql-8.3-605.jdbc3.jar"
//        ),
//        new UpgradeSymlink(
//            "common/lib/postgresql.jar",
//            "../../../../opt/postgresql-9.2/share/java/postgresql.jar",
//            "../../../../opt/postgresql-9.2-i686/share/java/postgresql.jar"
//        ),
//        new UpgradeSymlink(
//            "server/lib/postgresql.jar",
//            "../../../../opt/postgresql-9.2/share/java/postgresql.jar",
//            "../../../../opt/postgresql-9.2-i686/share/java/postgresql.jar"
//        )
//    };
}
