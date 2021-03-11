/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2000-2013, 2015, 2016, 2017, 2018, 2019, 2020, 2021  AO Industries, Inc.
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
 * along with aoserv-daemon.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.email;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.email.Address;
import com.aoindustries.aoserv.client.email.BlackholeAddress;
import com.aoindustries.aoserv.client.email.Domain;
import com.aoindustries.aoserv.client.email.Forwarding;
import com.aoindustries.aoserv.client.email.InboxAddress;
import com.aoindustries.aoserv.client.email.ListAddress;
import com.aoindustries.aoserv.client.email.PipeAddress;
import com.aoindustries.aoserv.client.email.SystemAlias;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.IoUtils;
import com.aoindustries.io.NullOutputStream;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.lang.Strings;
import com.aoindustries.net.DomainName;
import com.aoindustries.net.Email;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author  AO Industries, Inc.
 */
final public class EmailAddressManager extends BuilderThread {

	private static final Logger logger = Logger.getLogger(EmailAddressManager.class.getName());

	/**
	 * Sendmail files.
	 */
	private static final UnixFile
		aliases = new UnixFile("/etc/aliases"),
		userTable = new UnixFile("/etc/mail/virtusertable")
	;

	/**
	 * Basic system aliases -- these MUST be present.
	 */
	private static final String
		MAILER_DAEMON = "mailer-daemon",
		POSTMASTER = "postmaster";

	private static EmailAddressManager emailAddressManager;

	private EmailAddressManager() {
	}

	private static void writeRequiredSystemAlias(String requiredAddress, List<SystemAlias> systemAliases, Set<String> usernamesUsed, ChainWriter aliasesOut) throws SQLException {
		boolean foundAddress = false;
		Iterator<SystemAlias> iter = systemAliases.iterator();
		while(iter.hasNext()) {
			SystemAlias alias = iter.next();
			String address = alias.getAddress();
			if(requiredAddress.equalsIgnoreCase(address)) {
				if(foundAddress) throw new SQLException("Duplicate system alias \"" + requiredAddress + "\"");
				foundAddress = true;
				iter.remove();
				usernamesUsed.add(address);
				aliasesOut.write(address).write(": ").write(alias.getDestination()).write('\n');
			}
		}
		if(!foundAddress) throw new SQLException("Required system alias \"" + requiredAddress + "\" not found");
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			Server thisServer = AOServDaemon.getThisServer();
			OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			// Use pseudo-random number generated seeded by secure random
			Random random = AOServDaemon.getFastRandom();

			synchronized(rebuildLock) {
				Set<UnixFile> restorecon = new LinkedHashSet<>();
				try {
					List<SystemAlias> seas = thisServer.getSystemEmailAliases();
					List<Address> eas = thisServer.getEmailAddresses();
					List<Domain> eds = thisServer.getEmailDomains();
					assert eas.isEmpty() || !eds.isEmpty() : "Email addresses exist without any domains";

					// Install sendmail if needed
					// Sendmail is enabled/disabled in SendmailCFManager based on net_binds
					boolean sendmailInstalled;
					if(!eds.isEmpty()) {
						PackageManager.installPackage(PackageManager.PackageName.SENDMAIL);
						sendmailInstalled = true;
					} else {
						sendmailInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.SENDMAIL) != null;
					}

					// Each username may only be used once within the aliases file
					Set<String> usernamesUsed = new HashSet<>();

					// Build the new /etc/aliases file contents
					ByteArrayOutputStream aliasesBOut = new ByteArrayOutputStream();
					// Build the new /etc/mail/virtusertable contents
					ByteArrayOutputStream usersBOut = new ByteArrayOutputStream();
					try (
						ChainWriter aliasesOut = new ChainWriter(aliasesBOut);
						ChainWriter usersOut = new ChainWriter(usersBOut)
					) {
						aliasesOut.print(
							"#\n"
							+ "#  Aliases in this file will NOT be expanded in the header from\n"
							+ "#  Mail, but WILL be visible over networks or from /bin/mail.\n"
							+ "#\n"
							+ "#\t>>>>>>>>>>\tThe program \"newaliases\" must be run after\n"
							+ "#\t>> NOTE >>\tthis file is updated for any changes to\n"
							+ "#\t>>>>>>>>>>\tshow through to sendmail.\n"
							+ "#\n"
							+ "# Generated by ").print(EmailAddressManager.class.getName()).print("\n");
						usersOut.print("# Generated by ").print(EmailAddressManager.class.getName()).print("\n");
						{
							List<SystemAlias> systemAliases = new ArrayList<>(seas);
							// Basic system aliases
							aliasesOut.print(
								"\n"
								+ "# Basic system aliases -- these MUST be present.\n"
							);
							writeRequiredSystemAlias(MAILER_DAEMON, systemAliases, usernamesUsed, aliasesOut);
							writeRequiredSystemAlias(POSTMASTER, systemAliases, usernamesUsed, aliasesOut);
							if(!systemAliases.isEmpty()) {
								// Additional system aliases
								aliasesOut.print(
									"\n"
									+ "# Additional system aliases.\n"
								);
								for(SystemAlias alias : systemAliases) {
									String address = alias.getAddress();
									usernamesUsed.add(address);
									aliasesOut.print(address).print(": ").println(alias.getDestination());
								}
							}
						}
						// Block default username-based aliases, all are listed in /etc/mail/virtusertable
						{
							String ex_nouser;
							if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
								//ex_nouser="/opt/aoserv-client/sbin/ex_nouser";
								// Code for EX_NOUSER in /usr/include/sysexits.h
								ex_nouser="\"/bin/sh -c 'exit 67'\"";
							} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
								// Code for EX_NOUSER in /usr/include/sysexits.h
								ex_nouser="\"exit 67\"";
							} else {
								throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
							}
							boolean didOne = false;
							for(UserServer lsa : thisServer.getLinuxServerAccounts()) {
								String username = lsa.getLinuxAccount().getUsername().getUsername().toString();
								if(!usernamesUsed.contains(username)) {
									if(username.indexOf('@') == -1) {
										if(!didOne) {
											aliasesOut.print(
												"\n"
												+ "# Block default username-based aliases to prevent unexpected cross-domain delivery.\n"
												+ "# Each specific address@domain combination is listed in /etc/mail/virtusertable.\n"
											);
											didOne = true;
										}
										aliasesOut.print(username).print(": |").println(ex_nouser);
									}
									usernamesUsed.add(username);
								}
							}
						}
						// Specific address@domain combinations
						String[] devNullUsername = new String[1];
						Map<Email, String> singleForwardingTies = new HashMap<>();
						Map<PosixPath, String> singleListTies = new HashMap<>();
						Map<String, String> singlePipeTies = new HashMap<>();
						Map<User.Name, String> singleInboxTies = new HashMap<>();
						{
							boolean didOne = false;
							for(Address ea : eas) {
								String address = ea.getAddress();
								if(address.length() > 0) {
									if(!didOne) {
										aliasesOut.print(
											"\n"
											+ "# Specific address@domain combinations.\n"
										);
										usersOut.print(
											"\n"
											+ "# Specific address@domain combinations.\n"
										);
										didOne = true;
									}
									writeEmailAddressConfigs(
										random,
										ea,
										usernamesUsed,
										devNullUsername,
										singleForwardingTies,
										singleListTies,
										singlePipeTies,
										singleInboxTies,
										aliasesOut,
										usersOut
									);
								}
							}
						}
						{
							boolean didOne = false;
							for(Domain ed : eds) {
								DomainName domain = ed.getDomain();
								for(SystemAlias sea : seas) {
									String address = sea.getAddress();
									if(ed.getEmailAddress(address) == null) {
										if(!didOne) {
											usersOut.print(
												"\n"
												+ "# Pass-through system aliases not otherwise specified.\n"
											);
											didOne = true;
										}
										usersOut.print(address).print('@').print(domain).print('\t').print(address).print('\n');
									}
								}
							}
						}
						{
							boolean didOne = false;
							for(Address ea : eas) {
								String address = ea.getAddress();
								if(address.length() == 0) {
									if(!didOne) {
										aliasesOut.print(
											"\n"
											+ "# Catch-all @domain addresses.\n"
										);
										usersOut.print(
											"\n"
											+ "# Catch-all @domain addresses.\n"
										);
										didOne = true;
									}
									writeEmailAddressConfigs(
										random,
										ea,
										usernamesUsed,
										devNullUsername,
										singleForwardingTies,
										singleListTies,
										singlePipeTies,
										singleInboxTies,
										aliasesOut,
										usersOut
									);
								}
							}
						}
					}

					// Only write to disk if changed, this will almost always be the case when
					// tie usernames are used for any reason, but this will help for servers with
					// simple configurations.
					boolean needMakeMap = sendmailInstalled && DaemonFileUtils.atomicWrite(
						userTable,
						usersBOut.toByteArray(),
						0644,
						UnixFile.ROOT_UID,
						UnixFile.ROOT_GID,
						null,
						restorecon
					);
					boolean needNewAliases = DaemonFileUtils.atomicWrite(
						aliases,
						aliasesBOut.toByteArray(),
						0644,
						UnixFile.ROOT_UID,
						UnixFile.ROOT_GID,
						null,
						restorecon
					);

					// SELinux before next steps
					DaemonFileUtils.restorecon(restorecon);
					restorecon.clear();

					if(sendmailInstalled) {
						// Rebuild the hash map
						if(needMakeMap) makeMap();

						// Call newaliases
						if(needNewAliases) newAliases();
					}
				} finally {
					DaemonFileUtils.restorecon(restorecon);
				}
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			logger.log(Level.SEVERE, null, T);
			return false;
		}
	}

	private static void writeEmailAddressConfigs(
		Random random,
		Address ea,
		Set<String> usernamesUsed,
		String[] devNullUsername,
		Map<Email, String> singleForwardingTies,
		Map<PosixPath, String> singleListTies,
		Map<String, String> singlePipeTies,
		Map<User.Name, String> singleInboxTies,
		ChainWriter aliasesOut,
		ChainWriter usersOut
	) throws IOException, SQLException {
		String address = ea.getAddress();
		DomainName domain = ea.getDomain().getDomain();

		/*
		 * The possible email deliveries:
		 *
		 * 1) /dev/null only
		 * 2) One forwarding destination, BEA ignored (use singleForwardingTies)
		 * 3) One email list, BEA ignored (use singleListTies)
		 * 4) One pipe, BEA ignored (use singlePipeTies)
		 * 5) One Inbox only, BEA ignored (use singleInboxTies)
		 * 6) Multiple destinations, BEA ignored (list each)
		 * 7) Nothing (ignore)
		 */
		BlackholeAddress bea = ea.getBlackholeEmailAddress();
		List<Forwarding> efs = ea.getEmailForwardings();
		// We need to not forward email for disabled accounts, but do we just reject it instead?
		// List<Forwarding> efs = ea.getEnabledEmailForwardings();
		List<ListAddress> elas = ea.getEnabledEmailListAddresses();
		List<PipeAddress> epas = ea.getEnabledEmailPipeAddresses();
		List<InboxAddress> laas = ea.getLinuxAccAddresses();

		String tieUsername;

		if(
			bea != null
			&& efs.isEmpty()
			&& elas.isEmpty()
			&& epas.isEmpty()
			&& laas.isEmpty()
		) {
			// 1) /dev/null only
			tieUsername = devNullUsername[0];
			if(tieUsername == null) {
				tieUsername = getTieUsername(random, usernamesUsed);
				devNullUsername[0] = tieUsername;
				aliasesOut.print(tieUsername).println(": /dev/null");
			}
		} else if(
			efs.size() == 1
			&& elas.isEmpty()
			&& epas.isEmpty()
			&& laas.isEmpty()
		) {
			// 2) One forwarding destination, BEA ignored (use singleForwardingTies)
			Email destination = efs.get(0).getDestination();
			tieUsername = singleForwardingTies.get(destination);
			if(tieUsername == null) {
				tieUsername = getTieUsername(random, usernamesUsed);
				singleForwardingTies.put(destination, tieUsername);
				aliasesOut.print(tieUsername).print(": ").println(destination);
			}
		} else if(
			efs.isEmpty()
			&& elas.size() == 1
			&& epas.isEmpty()
			&& laas.isEmpty()
		) {
			// 3)  One email list, BEA ignored (use singleListTies)
			PosixPath path = elas.get(0).getEmailList().getPath();
			tieUsername = singleListTies.get(path);
			if(tieUsername == null) {
				tieUsername = getTieUsername(random, usernamesUsed);
				singleListTies.put(path, tieUsername);
				aliasesOut.print(tieUsername).print(": :include:").println(path);
			}
		} else if(
			efs.isEmpty()
			&& elas.isEmpty()
			&& epas.size() == 1
			&& laas.isEmpty()
		) {
			// 4) One pipe, BEA ignored (use singlePipeTies)
			String command = epas.get(0).getEmailPipe().getCommand();
			tieUsername = singlePipeTies.get(command);
			if(tieUsername == null) {
				tieUsername = getTieUsername(random, usernamesUsed);
				singlePipeTies.put(command, tieUsername);
				aliasesOut.print(tieUsername).print(": \"| ").print(command).println('"');
			}
		} else if(
			efs.isEmpty()
			&& elas.isEmpty()
			&& epas.isEmpty()
			&& laas.size() == 1
		) {
			// 5) One Inbox only, BEA ignored (use singleInboxTies)
			UserServer lsa = laas.get(0).getLinuxServerAccount();
			if(lsa != null) {
				User.Name username = lsa.getLinuxAccount_username_id();
				tieUsername = singleInboxTies.get(username);
				if(tieUsername == null) {
					tieUsername = getTieUsername(random, usernamesUsed);
					singleInboxTies.put(username, tieUsername);
					aliasesOut.print(tieUsername).print(": \\").println(Strings.replace(username.toString(), '@', "\\@"));
				}
			} else {
				tieUsername = null;
			}
		} else if(
			!efs.isEmpty()
			|| !elas.isEmpty()
			|| !epas.isEmpty()
			|| !laas.isEmpty()
		) {
			// 6) Multiple destinations, BEA ignored (list each)
			tieUsername = getTieUsername(random, usernamesUsed);
			aliasesOut.print(tieUsername).print(": ");
			boolean done = false;
			for(Forwarding ef : efs) {
				if(done) aliasesOut.print(",\n\t");
				else done = true;
				aliasesOut.print(ef.getDestination());
			}
			for(ListAddress ela : elas) {
				if(done) aliasesOut.print(",\n\t");
				else done = true;
				aliasesOut.print(":include:").print(ela.getEmailList().getPath());
			}
			for(PipeAddress epa : epas) {
				if(done) aliasesOut.print(",\n\t");
				else done = true;
				aliasesOut.print("\"| ").print(epa.getEmailPipe().getCommand()).print('"');
			}
			for(InboxAddress laa : laas) {
				if(done) aliasesOut.print(",\n\t");
				else done = true;
				aliasesOut.print('\\').print(Strings.replace(laa.getLinuxServerAccount().getLinuxAccount().getUsername().getUsername().toString(),'@',"\\@"));
			}
			aliasesOut.println();
		} else {
			// 7) Not used - ignore
			tieUsername = null;
		}

		if(tieUsername != null) usersOut.print(address).print('@').print(domain).print('\t').println(tieUsername);
	}

	private static final int TIE_USERNAME_DIGITS = 12;
	private static final String TIE_PREFIX = "tmp_";
	private static final String TIE_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz";

	private static String getTieUsername(Random random, Set<String> usernamesUsed) {
		StringBuilder SB = new StringBuilder(4 + TIE_USERNAME_DIGITS);
		SB.append(TIE_PREFIX);
		while(true) {
			SB.setLength(TIE_PREFIX.length());
			for(int c = 0; c < TIE_USERNAME_DIGITS; c++) {
				SB.append(
					TIE_CHARS.charAt(
						random.nextInt(TIE_CHARS.length())
					)
				);
			}
			String username = SB.toString();
			if(usernamesUsed.add(username)) {
				return username;
			}
		}
	}

	private static final Object makeMapLock = new Object();
	private static void makeMap() throws IOException, SQLException {
		synchronized(makeMapLock) {
			// Run the command
			String makemap;
			OperatingSystemVersion osv = AOServDaemon.getThisServer().getHost().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
			) {
				makemap = "/usr/sbin/makemap";
			} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			AOServDaemon.execRun(
				stdin -> {
					try (InputStream in = new FileInputStream(userTable.getPath())) {
						IoUtils.copy(in, stdin);
					}
				},
				stdout -> IoUtils.copy(stdout, NullOutputStream.getInstance()), // Do nothing with the output
				makemap,
				"hash",
				userTable.getPath()
			);
		}
	}

	public static void start() throws IOException, SQLException {
		Server thisServer = AOServDaemon.getThisServer();
		OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
		int osvId = osv.getPkey();

		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(EmailAddressManager.class)
				&& emailAddressManager == null
			) {
				System.out.print("Starting EmailAddressManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					emailAddressManager = new EmailAddressManager();
					conn.getEmail().getDomain().addTableListener(emailAddressManager, 0);
					conn.getEmail().getBlackholeAddress().addTableListener(emailAddressManager, 0);
					conn.getEmail().getAddress().addTableListener(emailAddressManager, 0);
					conn.getEmail().getForwarding().addTableListener(emailAddressManager, 0);
					conn.getEmail().getList().addTableListener(emailAddressManager, 0);
					conn.getEmail().getListAddress().addTableListener(emailAddressManager, 0);
					conn.getEmail().getPipe().addTableListener(emailAddressManager, 0);
					conn.getEmail().getPipeAddress().addTableListener(emailAddressManager, 0);
					conn.getLinux().getUserServer().addTableListener(emailAddressManager, 0);
					conn.getEmail().getInboxAddress().addTableListener(emailAddressManager, 0);
					conn.getBilling().getPackage().addTableListener(emailAddressManager, 0);
					conn.getEmail().getSystemAlias().addTableListener(emailAddressManager, 0);
					PackageManager.addPackageListener(emailAddressManager);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	private static final Object newAliasesLock = new Object();
	private static void newAliases() throws IOException {
		synchronized(newAliasesLock) {
			// Run the command
			AOServDaemon.exec("/usr/bin/newaliases");
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild Email Addresses";
	}
}
