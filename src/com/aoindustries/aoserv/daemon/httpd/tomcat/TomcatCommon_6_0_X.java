package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2008-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.io.ChainWriter;
import java.io.IOException;
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

    public String getDefaultJdkVersion() {
        return "1.6.0";
    }
}
