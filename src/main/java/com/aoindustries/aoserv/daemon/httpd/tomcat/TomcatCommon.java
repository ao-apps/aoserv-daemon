/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2008-2013, 2015, 2017, 2018, 2020, 2021, 2022  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoapps.encoding.ChainWriter;
import com.aoindustries.aoserv.client.web.tomcat.ContextDataSource;
import com.aoindustries.aoserv.client.web.tomcat.ContextParameter;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
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
		// Do nothing
	}

	/**
	 * Gets any packages that must be installed for this site.
	 */
	protected abstract Set<PackageManager.PackageName> getRequiredPackages() throws IOException, SQLException;

	/**
	 * Writes a single parameter.
	 */
	public void writeHttpdTomcatParameter(ContextParameter parameter, ChainWriter out) throws IOException {
		out.print("          <Parameter\n"
				+ "            name=\"").textInXmlAttribute(parameter.getName()).print("\"\n"
				+ "            value=\"").textInXmlAttribute(parameter.getValue()).print("\"\n");
		boolean override = parameter.getOverride();
		if(!override) out.print("            override=\"").textInXmlAttribute(override).print("\"\n");
		String description = parameter.getDescription();
		if(description != null) out.print("            description=\"").textInXmlAttribute(description).print("\"\n");
		out.print("          />\n");
	}

	/**
	 * Writes a single data source.
	 */
	public abstract void writeHttpdTomcatDataSource(ContextDataSource dataSource, ChainWriter out) throws IOException, SQLException;

	/**
	 * Gets the name of the Tomcat directory under <code>/opt/</code>.
	 */
	protected abstract String getApacheTomcatDir();
}
