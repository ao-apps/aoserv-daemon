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
 * Some common code for Tomcat 4.1.X
 *
 * @author  AO Industries, Inc.
 */
class TomcatCommon_4_1_X extends TomcatCommon {

    private static final TomcatCommon_4_1_X instance = new TomcatCommon_4_1_X();
    static TomcatCommon_4_1_X getInstance() {
        return instance;
    }

    private TomcatCommon_4_1_X() {}

    public void writeHttpdTomcatDataSource(HttpdTomcatDataSource dataSource, ChainWriter out) throws SQLException {
        out.print("          <Resource\n"
                + "            name=\"").printXmlAttribute(dataSource.getName()).print("\"\n"
                + "            auth=\"Container\"\n"
                + "            type=\"javax.sql.DataSource\"\n"
                + "          />\n"
                + "          <ResourceParams name=\"").printXmlAttribute(dataSource.getName()).print("\">\n"
                + "            <parameter><name>factory</name><value>org.apache.commons.dbcp.BasicDataSourceFactory</value></parameter>\n"
                + "            <parameter><name>username</name><value>").printXmlBody(dataSource.getUsername()).print("</value></parameter>\n"
                + "            <parameter><name>password</name><value>").printXmlBody(dataSource.getPassword()).print("</value></parameter>\n"
                + "            <parameter><name>driverClassName</name><value>").printXmlBody(dataSource.getDriverClassName()).print("</value></parameter>\n"
                + "            <parameter><name>url</name><value>").printXmlBody(dataSource.getUrl()).print("</value></parameter>\n"
                + "            <parameter><name>maxActive</name><value>").print(dataSource.getMaxActive()).print("</value></parameter>\n"
                + "            <parameter><name>maxIdle</name><value>").print(dataSource.getMaxIdle()).print("</value></parameter>\n"
                + "            <parameter><name>maxWait</name><value>").print(dataSource.getMaxWait()).print("</value></parameter>\n");
        if(dataSource.getValidationQuery()!=null) {
            out.print("            <parameter><name>validationQuery</name><value>").printXmlBody(dataSource.getValidationQuery()).print("</value></parameter>\n");
        }
        out.print("            <parameter><name>removeAbandoned</name><value>true</value></parameter>\n"
                + "            <parameter><name>removeAbandonedTimeout</name><value>300</value></parameter>\n"
                + "            <parameter><name>logAbandoned</name><value>true</value></parameter>\n"
                + "          </ResourceParams>\n");
    }

    public String getDefaultJdkVersion() {
        return "1.5.0";
    }
}
