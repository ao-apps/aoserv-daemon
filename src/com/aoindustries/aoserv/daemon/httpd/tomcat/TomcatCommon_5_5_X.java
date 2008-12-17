package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.io.ChainWriter;
import java.sql.SQLException;

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

    void writeHttpdTomcatDataSource(HttpdTomcatDataSource dataSource, ChainWriter out) throws SQLException {
        out.print("          <Resource\n"
                + "            name=\"").printXmlAttribute(dataSource.getName()).print("\"\n"
                + "            auth=\"Container\"\n"
                + "            type=\"javax.sql.DataSource\"\n"
                + "            username=\"").printXmlAttribute(dataSource.getUsername()).print("\"\n"
                + "            password=\"").printXmlAttribute(dataSource.getPassword()).print("\"\n"
                + "            driverClassName=\"").printXmlAttribute(dataSource.getDriverClassName()).print("\"\n"
                + "            url=\"").printXmlAttribute(dataSource.getUrl()).print("\"\n"
                + "            maxActive=\"").print(dataSource.getMaxActive()).print("\"\n"
                + "            maxIdle=\"").print(dataSource.getMaxIdle()).print("\"\n"
                + "            maxWait=\"").print(dataSource.getMaxWait()).print("\"\n");
        if(dataSource.getValidationQuery()!=null) {
            out.print("            validationQuery=\"").printXmlAttribute(dataSource.getValidationQuery()).print("\"\n");
        }
        out.print("            removeAbandoned=\"true\"\n"
                + "            removeAbandonedTimeout=\"300\"\n"
                + "            logAbandoned=\"true\"\n"
                + "          />\n");
    }

    public String getDefaultJdkVersion() {
        return "1.5.0";
    }
}
