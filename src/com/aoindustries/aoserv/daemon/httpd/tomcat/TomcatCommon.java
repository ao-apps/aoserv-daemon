/*
 * Copyright 2008-2013, 2015, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.web.tomcat.HttpdTomcatDataSource;
import com.aoindustries.aoserv.client.web.tomcat.HttpdTomcatParameter;
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
	protected abstract Set<PackageManager.PackageName> getRequiredPackages() throws IOException, SQLException;

	/**
	 * Writes a single parameter.
	 */
	public void writeHttpdTomcatParameter(HttpdTomcatParameter parameter, ChainWriter out) throws IOException {
		out.print("          <Parameter\n"
				+ "            name=\"").encodeXmlAttribute(parameter.getName()).print("\"\n"
				+ "            value=\"").encodeXmlAttribute(parameter.getValue()).print("\"\n");
		boolean override = parameter.getOverride();
		if(!override) out.print("            override=\"").encodeXmlAttribute(override).print("\"\n");
		String description = parameter.getDescription();
		if(description != null) out.print("            description=\"").encodeXmlAttribute(description).print("\"\n");
		out.print("          />\n");
	}

	/**
	 * Writes a single data source.
	 */
	public abstract void writeHttpdTomcatDataSource(HttpdTomcatDataSource dataSource, ChainWriter out) throws IOException, SQLException;

	/**
	 * Gets the name of the Tomcat directory under <code>/opt/</code>.
	 */
	protected abstract String getApacheTomcatDir();
}
