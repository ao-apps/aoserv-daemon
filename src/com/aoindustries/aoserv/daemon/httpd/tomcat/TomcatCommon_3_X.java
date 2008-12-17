package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.io.ChainWriter;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Some common code for Tomcat 3.X
 *
 * @author  AO Industries, Inc.
 */
abstract class TomcatCommon_3_X extends TomcatCommon {

    TomcatCommon_3_X() {}

    /**
     * The list of files that are contained in /www/{site}/var/log directories.
     */
    static final String[] tomcatLogFiles={
        "jasper.log",
        "jvm_crashes.log",
        "servlet.log",
        "servlet_err",
        "tomcat.log"
    };

    void writeHttpdTomcatDataSource(HttpdTomcatDataSource dataSource, ChainWriter out) throws SQLException {
        throw new SQLException("TODO: Implement for Tomcat 3.X");
    }

    /**
     * Uses os-default JDK.
     * 
     * @see  OperatingSystemConfiguration#getDefaultJdkVersion
     */
    public String getDefaultJdkVersion() throws IOException, SQLException {
        return OperatingSystemConfiguration.getOperatingSystemConfiguration().getDefaultJdkVersion();
    }
}
