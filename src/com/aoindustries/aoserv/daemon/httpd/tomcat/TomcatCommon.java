package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.client.HttpdTomcatParameter;
import com.aoindustries.io.ChainWriter;
import java.io.IOException;
import java.sql.SQLException;

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
    void writeHttpdTomcatParameter(HttpdTomcatParameter parameter, ChainWriter out) {
        out.print("          <Parameter\n"
                + "            name=\"").printXmlAttribute(parameter.getName()).print("\"\n"
                + "            value=\"").printXmlAttribute(parameter.getValue()).print("\"\n"
                + "            override=\"").print(parameter.getOverride()).print("\"\n");
        if(parameter.getDescription()!=null) out.print("            description=\"").printXmlAttribute(parameter.getDescription()).print("\"\n");
        out.print("          />\n");
    }

    /**
     * Writes a single data source.
     */
    abstract void writeHttpdTomcatDataSource(HttpdTomcatDataSource dataSource, ChainWriter out) throws SQLException;

    /**
     * The default JDK version for this version of Tomcat, not including
     * any "jdk".
     */
    public abstract String getDefaultJdkVersion() throws IOException, SQLException;
}
