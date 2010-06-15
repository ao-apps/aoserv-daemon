package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2008-2010 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.daemon.util.UpgradeSymlink;
import com.aoindustries.io.ChainWriter;
import java.io.IOException;

/**
 * Some common code for all installations of Tomcat.
 *
 * @author  AO Industries, Inc.
 */
public abstract class TomcatCommon {

    TomcatCommon() {}

    /**
     * Writes a single paramter.
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
    public abstract void writeHttpdTomcatDataSource(HttpdTomcatDataSource dataSource, ChainWriter out) throws IOException;

    /**
     * The default JDK version for this version of Tomcat, not including
     * any "jdk".
     */
    public abstract String getDefaultJdkVersion() throws IOException;

    protected static final UpgradeSymlink[] upgradeSymlinks_MySQL = {
        new UpgradeSymlink("common/lib/mm.mysql-2.0.7-bin.jar", "../../../../usr/mm.mysql-2.0.7/mm.mysql-2.0.7-bin.jar", "../../../../opt/mm.mysql-2.0.7/mm.mysql-2.0.7-bin.jar"),
        new UpgradeSymlink("common/lib/mm.mysql-2.0.7-bin.jar", "/usr/mm.mysql/2.0.7/mm.mysql-2.0.7-bin.jar", "../../../../opt/mm.mysql-2.0.7/mm.mysql-2.0.7-bin.jar"),
        new UpgradeSymlink("common/lib/mysql-connector-java-3.0.11-stable-bin.jar", "../../../../usr/mysql-connector-java-3.0.11-stable/mysql-connector-java-3.0.11-stable-bin.jar", "../../../../opt/mysql-connector-java-3.0.11-stable/mysql-connector-java-3.0.11-stable-bin.jar"),
        new UpgradeSymlink("common/lib/mysql-connector-java-3.0.11-stable-bin.jar", "../../../../usr/mysql-connector-java/3.0.11-stable/mysql-connector-java-3.0.11-stable-bin.jar", "../../../../opt/mysql-connector-java-3.0.11-stable/mysql-connector-java-3.0.11-stable-bin.jar"),
        new UpgradeSymlink("common/lib/mysql-connector-java-3.1.12-bin.jar", "../../../../usr/mysql-connector-java/3.1.12/mysql-connector-java-3.1.12-bin.jar", "../../../../opt/mysql-connector-java-3.1.12/mysql-connector-java-3.1.12-bin.jar"),
        new UpgradeSymlink("common/lib/mysql-connector-java-3.1.12-bin.jar", "/usr/mysql-connector-java/3.1.12/mysql-connector-java-3.1.12-bin.jar", "../../../../opt/mysql-connector-java-3.1.12/mysql-connector-java-3.1.12-bin.jar")
    };

    protected static final UpgradeSymlink[] upgradeSymlinks_PostgreSQL = {
        new UpgradeSymlink(
            "common/lib/postgresql.jar",
            "../../../../usr/postgresql-7.1.3/share/java/postgresql.jar",
            "../../../../opt/postgresql-7.1-i686/share/java/postgresql.jar"
        ),
        new UpgradeSymlink(
            "common/lib/postgresql.jar",
            "../../../../opt/postgresql-7.1/share/java/postgresql.jar",
            "../../../../opt/postgresql-7.1-i686/share/java/postgresql.jar"
        ),
        new UpgradeSymlink(
            "common/lib/postgresql.jar",
            "../../../../usr/postgresql-7.3/share/java/postgresql.jar",
            "../../../../opt/postgresql-7.3/share/java/postgresql.jar"
        ),
        new UpgradeSymlink(
            "common/lib/postgresql.jar",
            "../../../../usr/postgresql/7.3/share/java/postgresql.jar",
            "../../../../opt/postgresql-7.3/share/java/postgresql.jar"
        ),
        new UpgradeSymlink(
            "common/lib/postgresql.jar",
            "../../../../usr/postgresql-7.3.3/share/java/postgresql.jar",
            "../../../../opt/postgresql-7.3/share/java/postgresql.jar"
        ),
        new UpgradeSymlink(
            "common/lib/postgresql.jar",
            "../../../../usr/postgresql/7.3.3/share/java/postgresql.jar",
            "../../../../opt/postgresql-7.3/share/java/postgresql.jar"
        ),
        new UpgradeSymlink(
            "common/lib/postgresql.jar",
            "../../../../usr/postgresql/8.1/share/java/postgresql.jar",
            "../../../../opt/postgresql-8.1/share/java/postgresql.jar"
        )
    };
}
