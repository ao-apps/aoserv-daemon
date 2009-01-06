package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2008-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.io.ChainWriter;
import java.sql.SQLException;

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

    public void writeHttpdTomcatDataSource(HttpdTomcatDataSource dataSource, ChainWriter out) throws SQLException {
        throw new SQLException("TODO: Implement for Tomcat 6.0.X");
    }

    public String getDefaultJdkVersion() {
        return "1.6.0";
    }
}
