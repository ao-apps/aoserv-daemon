/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.ssl;

import static com.aoindustries.aoserv.client.AlertLevel.*;
import com.aoindustries.aoserv.client.CyrusImapdBind;
import com.aoindustries.aoserv.client.CyrusImapdServer;
import com.aoindustries.aoserv.client.HttpdSiteBind;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.SendmailServer;
import com.aoindustries.aoserv.client.SslCertificate;
import static com.aoindustries.aoserv.client.SslCertificate.Check;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.StringUtility;
import com.aoindustries.util.Tuple2;
import com.aoindustries.util.concurrent.ConcurrencyLimiter;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

final public class SslCertificateManager {

	private static UnixFile getUnixFile(UnixPath path) {
		return (path == null) ? null : new UnixFile(path.toString());
	}

	private static final String ALGORITHM = "SHA-256";

	private static final long CERTBOT_CACHE_DURATION = 60L * 60 * 1000; // One hour

	private static final int CERTBOT_CRITICAL_DAYS = 0;
	private static final int CERTBOT_HIGH_DAYS = 7;
	private static final int CERTBOT_MEDIUM_DAYS = 10;
	private static final int CERTBOT_LOW_DAYS = 12;

	private static final Map<Tuple2<UnixFile,String>,Tuple2<Long,String>> getHashedCache = new HashMap<>();

	/**
	 * Gets the SHA-256 hashed output from a command, caching results when the file has not changed modified times.
	 *
	 * @implNote  This synchronizes on {@link #getHashedCache} which will serialize all commands.  This is OK as results will be cached normally.
	 */
	private static String getCommandHash(UnixFile file, String type, long modifiedTime, String ... command) throws IOException {
		try {
			Tuple2<UnixFile,String> cacheKey = new Tuple2<>(file, type);
			synchronized(getHashedCache) {
				Tuple2<Long,String> cached = getHashedCache.get(cacheKey);
				if(cached != null && cached.getElement1() == modifiedTime) return cached.getElement2();
				String hashed = StringUtility.convertToHex(
					MessageDigest.getInstance(ALGORITHM).digest(
						AOServDaemon.execAndCaptureBytes(command)
					)
				);
				getHashedCache.put(cacheKey, new Tuple2<>(modifiedTime, hashed));
				return hashed;
			}
		} catch(NoSuchAlgorithmException e) {
			throw new AssertionError(ALGORITHM + " is expected to exist", e);
		}
	}

	private static class CertbotStatus {
		private final long cacheTime;
		private final UnixFile certCanonicalFile;
		private final long certModifyTime;
		private final UnixFile chainCanonicalFile;
		private final long chainModifyTime;
		private final UnixFile fullchainCanonicalFile;
		private final long fullchainModifyTime;
		private final UnixFile privkeyCanonicalFile;
		private final long privkeyModifyTime;
		private final long renewalModifyTime;

		private final String status;
		private final int days;

		private CertbotStatus(
			long cacheTime,
			UnixFile certCanonicalFile,
			long certModifyTime,
			UnixFile chainCanonicalFile,
			long chainModifyTime,
			UnixFile fullchainCanonicalFile,
			long fullchainModifyTime,
			UnixFile privkeyCanonicalFile,
			long privkeyModifyTime,
			long renewalModifyTime,
			String status,
			int days
		) {
			this.cacheTime = cacheTime;
			this.certCanonicalFile = certCanonicalFile;
			this.certModifyTime = certModifyTime;
			this.chainCanonicalFile = chainCanonicalFile;
			this.chainModifyTime = chainModifyTime;
			this.fullchainCanonicalFile = fullchainCanonicalFile;
			this.fullchainModifyTime = fullchainModifyTime;
			this.privkeyCanonicalFile = privkeyCanonicalFile;
			this.privkeyModifyTime = privkeyModifyTime;
			this.renewalModifyTime = renewalModifyTime;
			this.status = status;
			this.days = days;
		}

		private String getStatus() {
			return status;
		}

		/**
		 * @return  the number of days valid or {@code -1} if invalid
		 */
		private int getDays() {
			return days;
		}
	}

	private static final Map<String,CertbotStatus> certbotCache = new HashMap<>();
	/**
	 * Gets the certificate status from certbot.
	 */
	private static CertbotStatus getCertbotStatus(String certbotName) throws IOException {
		synchronized(certbotCache) {
			long currentTime = System.currentTimeMillis();
			UnixFile certCanonicalFile = new UnixFile(new File("/etc/letsencrypt/live/" + certbotName + "/cert.pem").getCanonicalFile());
			long certModifyTime = certCanonicalFile.getStat().getModifyTime();
			UnixFile chainCanonicalFile = new UnixFile(new File("/etc/letsencrypt/live/" + certbotName + "/chain.pem").getCanonicalFile());
			long chainModifyTime = chainCanonicalFile.getStat().getModifyTime();
			UnixFile fullchainCanonicalFile = new UnixFile(new File("/etc/letsencrypt/live/" + certbotName + "/fullchain.pem").getCanonicalFile());
			long fullchainModifyTime = fullchainCanonicalFile.getStat().getModifyTime();
			UnixFile privkeyCanonicalFile = new UnixFile(new File("/etc/letsencrypt/live/" + certbotName + "/privkey.pem").getCanonicalFile());
			long privkeyModifyTime = privkeyCanonicalFile.getStat().getModifyTime();
			UnixFile renewalFile = new UnixFile("/etc/letsencrypt/renewal/" + certbotName + ".conf");
			long renewalModifyTime = renewalFile.getStat().getModifyTime();
			CertbotStatus cached = certbotCache.get(certbotName);
			if(
				cached != null
				&& (currentTime - cached.cacheTime) < CERTBOT_CACHE_DURATION
				&& (cached.cacheTime - currentTime) < CERTBOT_CACHE_DURATION
				&& certCanonicalFile.equals(cached.certCanonicalFile)
				&& certModifyTime == cached.certModifyTime
				&& chainCanonicalFile.equals(cached.chainCanonicalFile)
				&& chainModifyTime == cached.chainModifyTime
				&& fullchainCanonicalFile.equals(cached.fullchainCanonicalFile)
				&& fullchainModifyTime == cached.fullchainModifyTime
				&& privkeyCanonicalFile.equals(cached.privkeyCanonicalFile)
				&& privkeyModifyTime == cached.privkeyModifyTime
				&& renewalModifyTime == cached.renewalModifyTime
			) {
				return cached;
			}
			String status = "UNKNOWN";
			int days = -1;
			try (
				BufferedReader in = new BufferedReader(
					new StringReader(
						AOServDaemon.execAndCapture("certbot", "certificates", "--cert-name", certbotName)
					)
				)
			) {
				String line;
				while((line = in.readLine()) != null) {
					if(line.startsWith("  Certificate Name: ")) {
						if(!certbotName.equals(line.substring("  Certificate Name: ".length()))) {
							throw new IOException("Unexpected certificate name: " + line);
						}
					}
					// TODO: Parse Domains?
					if(line.startsWith("    Expiry Date: ")) {
						int leftPos = line.indexOf('(');
						if(leftPos != -1) {
							int rightPos = line.indexOf(')', leftPos + 1);
							if(rightPos != -1) {
								String substr = line.substring(leftPos + 1, rightPos).trim();
								int colonPos = substr.indexOf(':');
								if(colonPos == -1) {
									status = substr.trim();
									days = -1;
								} else {
									try {
										String daysString = substr.substring(colonPos + 1).trim();
										if(daysString.endsWith(" days")) {
											daysString = daysString.substring(0, daysString.length() - " days".length()).trim();
										} else if(daysString.endsWith(" day")) {
											daysString = daysString.substring(0, daysString.length() - " day".length()).trim();
										}
										days = Integer.parseInt(daysString);
										if(days < -1) days = -1;
										status = substr.substring(0, colonPos).trim();
									} catch(NumberFormatException e) {
										status = substr;
										days = -1;
									}
								}
								break;
							}
						}
					}
				}
			}
			CertbotStatus result = new CertbotStatus(
				currentTime,
				certCanonicalFile,
				certModifyTime,
				chainCanonicalFile,
				chainModifyTime,
				fullchainCanonicalFile,
				fullchainModifyTime,
				privkeyCanonicalFile,
				privkeyModifyTime,
				renewalModifyTime,
				status,
				days
			);
			certbotCache.put(certbotName, result);
			return result;
		}
	}

	private static final ConcurrencyLimiter<SslCertificate,List<Check>> checkSslCertificateConcurrencyLimiter = new ConcurrencyLimiter<>();

	public static List<Check> checkSslCertificate(SslCertificate certificate) throws IOException, SQLException {
		try {
			OperatingSystemVersion osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			boolean isNewOpenssl;
			switch(osvId) {
				case OperatingSystemVersion.CENTOS_5_DOM0_I686 :
				case OperatingSystemVersion.CENTOS_5_DOM0_X86_64 :
				case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 :
					isNewOpenssl = false;
					break;
				case OperatingSystemVersion.CENTOS_7_DOM0_X86_64 :
				case OperatingSystemVersion.CENTOS_7_X86_64 :
					isNewOpenssl = true;
					break;
				default :
					throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
			}
			return checkSslCertificateConcurrencyLimiter.executeSerialized(
				certificate,
				() -> {
					List<Check> results = new ArrayList<>();

					// First make sure all expected files exist
					UnixFile keyFile   = getUnixFile(certificate.getKeyFile());
					UnixFile csrFile   = getUnixFile(certificate.getCsrFile());
					UnixFile certFile  = getUnixFile(certificate.getCertFile());
					UnixFile chainFile = getUnixFile(certificate.getChainFile());

					// Stat each file
					Stat keyStat   = keyFile.getStat();
					Stat csrStat   = (csrFile == null) ? null : csrFile.getStat();
					Stat certStat  = certFile.getStat();
					Stat chainStat = (chainFile == null) ? null : chainFile.getStat();

					// Make sure each expected file exists
					boolean keyExists = keyStat.exists();
					results.add(new Check("Key exists?", Boolean.toString(keyExists), keyExists ? NONE : CRITICAL, keyFile.toString()));
					boolean csrExists;
					if(csrStat != null) {
						csrExists = csrStat.exists();
						results.add(new Check("CSR exists?", Boolean.toString(csrExists), csrExists ? NONE : MEDIUM, csrFile.toString()));
					} else {
						csrExists = false;
					}
					boolean certExists = certStat.exists();
					results.add(new Check("Cert exists?", Boolean.toString(certExists), certExists ? NONE : CRITICAL, certFile.toString()));
					boolean chainExists;
					if(chainStat != null) {
						chainExists = chainStat.exists();
						results.add(new Check("Chain exists?", Boolean.toString(chainExists), chainExists ? NONE : CRITICAL, chainFile.toString()));
					} else {
						chainExists = false;
					}

					// Follow symbolic links to target
					UnixFile keyCanonical;
					Stat keyCanonicalStat;
					boolean keyCanonicalExists;
					if(keyExists && keyStat.isSymLink()) {
						keyCanonical = new UnixFile(keyFile.getFile().getCanonicalPath());
						keyCanonicalStat = keyCanonical.getStat();
						keyCanonicalExists = keyCanonicalStat.exists();
						results.add(new Check("Canonical key exists?", Boolean.toString(keyCanonicalExists), keyCanonicalExists ? NONE : CRITICAL, keyCanonical.toString()));
					} else {
						keyCanonical = keyFile;
						keyCanonicalStat = keyStat;
						keyCanonicalExists = keyExists;
					}
					UnixFile csrCanonical;
					Stat csrCanonicalStat;
					boolean csrCanonicalExists;
					if(csrExists && csrStat.isSymLink()) {
						csrCanonical = new UnixFile(csrFile.getFile().getCanonicalPath());
						csrCanonicalStat = csrCanonical.getStat();
						csrCanonicalExists = csrCanonicalStat.exists();
						results.add(new Check("Canonical CSR exists?", Boolean.toString(csrCanonicalExists), csrCanonicalExists ? NONE : MEDIUM, csrCanonical.toString()));
					} else {
						csrCanonical = csrFile;
						csrCanonicalStat = csrStat;
						csrCanonicalExists = csrExists;
					}
					UnixFile certCanonical;
					Stat certCanonicalStat;
					boolean certCanonicalExists;
					if(certExists && certStat.isSymLink()) {
						certCanonical = new UnixFile(certFile.getFile().getCanonicalPath());
						certCanonicalStat = certCanonical.getStat();
						certCanonicalExists = certCanonicalStat.exists();
						results.add(new Check("Canonical cert exists?", Boolean.toString(certCanonicalExists), certCanonicalExists ? NONE : CRITICAL, certCanonical.toString()));
					} else {
						certCanonical = certFile;
						certCanonicalStat = certStat;
						certCanonicalExists = certExists;
					}
					UnixFile chainCanonical;
					Stat chainCanonicalStat;
					boolean chainCanonicalExists;
					if(chainExists && chainStat.isSymLink()) {
						chainCanonical = new UnixFile(chainFile.getFile().getCanonicalPath());
						chainCanonicalStat = chainCanonical.getStat();
						chainCanonicalExists = chainCanonicalStat.exists();
						results.add(new Check("Canonical chain exists?", Boolean.toString(chainCanonicalExists), chainCanonicalExists ? NONE : CRITICAL, chainCanonical.toString()));
					} else {
						chainCanonical = chainFile;
						chainCanonicalStat = chainStat;
						chainCanonicalExists = chainExists;
					}

					// Get the last modified of each file, or 0 for a file that doesn't exist
					long keyModifyTime   = keyCanonicalExists   ? keyCanonicalStat  .getModifyTime() : 0;
					long csrModifyTime   = csrCanonicalExists   ? csrCanonicalStat  .getModifyTime() : 0;
					long certModifyTime  = certCanonicalExists  ? certCanonicalStat .getModifyTime() : 0;
					long chainModifyTime = chainCanonicalExists ? chainCanonicalStat.getModifyTime() : 0; // TODO: How to verify chain?

					// New: Compare by public key: https://www.sslshopper.com/certificate-key-matcher.html
					// Old: Compare by modulus: https://support.asperasoft.com/hc/en-us/articles/216128468-OpenSSL-commands-to-check-and-verify-your-SSL-certificate-key-and-CSR
					String keyHash  = keyCanonicalExists  ? getCommandHash(
						keyCanonical,
						isNewOpenssl ? "pkey" : "rsa",
						keyModifyTime,
						isNewOpenssl
							? new String[] {"openssl", "pkey", "-outform", "PEM", "-in", keyCanonical.getPath(), "-pubout"}
							: new String[] {"openssl", "rsa", "-in", keyCanonical.getPath(), "-noout", "-modulus"}
					) : null;
					String csrHash  = csrCanonicalExists  ? getCommandHash(
						csrCanonical,
						"req",
						csrModifyTime,
						isNewOpenssl
							? new String[] {"openssl", "req", "-outform", "PEM", "-in", csrCanonical.getPath(), "-pubkey", "-noout"}
							: new String[] {"openssl", "req", "-in", csrCanonical.getPath(), "-noout", "-modulus"}
					) : null;
					String certHash = certCanonicalExists ? getCommandHash(
						certCanonical,
						"x509",
						certModifyTime,
						isNewOpenssl
							? new String[] {"openssl", "x509", "-outform", "PEM", "-in", certCanonical.getPath(), "-pubkey", "-noout"}
							: new String[] {"openssl", "x509", "-in", certCanonical.getPath(), "-noout", "-modulus"}
					) : null;
					// TODO: Do we need to support both cert and fullchain files no SslCertificate class?  Check both with x509 command for match
					// TODO: PostgreSQL uses fullchain for Let's Encrypt.
					if(keyHash != null) {
						results.add(new Check("Key " + ALGORITHM, keyHash, NONE, null));
					}
					if(csrHash != null) {
						if(keyHash == null || keyHash.equals(csrHash)) {
							results.add(new Check("CSR " + ALGORITHM, csrHash, NONE, null));
						} else {
							results.add(new Check("CSR " + ALGORITHM, csrHash, MEDIUM, "CSR does not match Key"));
						}
					}
					if(certHash != null) {
						if(keyHash == null || keyHash.equals(certHash)) {
							results.add(new Check("Cert " + ALGORITHM, certHash, NONE, null));
						} else {
							results.add(new Check("Cert " + ALGORITHM, certHash, CRITICAL, "Cert does not match Key"));
						}
					}
					

					// TODO: cyrus and sendmail copies match (for let's encrypt) (mysql, postgresql later)
					// TODO: cyrus and sendmail permissions as expected (possibly on copy) (mysql, postgresql later)

					// Configuration:
					// TODO: DNS settings
					// TODO: Hostnames match what is used by (case-insensitive match)
					// TODO: No extra hostnames - low level warning if any found

					// TODO: common name is exactly as expected (case-sensitive) - low if matches case-insensitive
					// TODO: subject alt names are as expected (case-sensitive) - low if matches case-insensitive

					// TODO: Expiration date
					// TODO: Let's Encrypt: low: 12 days, medium 10 days, high 7 days, critical expired
					// TODO: Others:        low: 30 days, medium 14 days, high 7 days, critical expired

					// TODO: Self-signed as low
					// TODO: Untrusted by openssl as high

					// TODO: Certificate max alert level setting? (with an "until" date?) - this used to cap in NOC or restrict statuses here?

					// Let's Encrypt certificate status
					String certbotName = certificate.getCertbotName();
					if(certbotName != null) {
						// TODO: Parse and compare Domains, too?
						CertbotStatus certbotStatus = getCertbotStatus(certbotName);
						String status = certbotStatus.getStatus();
						results.add(new Check("Certbot status", status, "VALID".equals(status) ? NONE : CRITICAL, null));
						int days = certbotStatus.getDays();
						results.add(
							new Check(
								"Certbot days left",
								days == -1 ? "EXPIRED" : Integer.toString(days),
								days == -1 || days < CERTBOT_CRITICAL_DAYS ? CRITICAL
									: days < CERTBOT_HIGH_DAYS ? HIGH
									: days < CERTBOT_MEDIUM_DAYS ? MEDIUM
									: days < CERTBOT_LOW_DAYS ? LOW
									: NONE,
								null
							)
						);
					}

					// TODO: Make sure copies up-to-date and with correct permissions and ownership (PostgreSQL, MySQL, Sendmail, Cyrus...)

					// Low-level if certificate appears unused
					List<CyrusImapdBind> cyrusBinds = certificate.getCyrusImapdBinds();
					List<CyrusImapdServer> cyrusServers = certificate.getCyrusImapdServers();
					List<HttpdSiteBind> hsbs = certificate.getHttpdSiteBinds();
					List<SendmailServer> sendmailServers = certificate.getSendmailServersByServerCertificate();
					List<SendmailServer> sendmailClients = certificate.getSendmailServersByClientCertificate();
					int useCount = 0;
					StringBuilder usedBy = new StringBuilder();
					if(!cyrusBinds.isEmpty()) {
						int size = cyrusBinds.size();
						useCount += size;
						usedBy.append(size).append(size == 1 ? " CyrusImapdBind" : " CyrusImapdBinds");
					}
					if(!cyrusServers.isEmpty()) {
						if(usedBy.length() > 0) usedBy.append(", ");
						int size = cyrusServers.size();
						useCount += size;
						usedBy.append(size).append(size == 1 ? " CyrusImapdServer" : " CyrusImapdServer");
					}
					if(!hsbs.isEmpty()) {
						if(usedBy.length() > 0) usedBy.append(", ");
						int size = hsbs.size();
						useCount += size;
						usedBy.append(size).append(size == 1 ? " HttpdSiteBind" : " HttpdSiteBind");
					}
					if(!sendmailServers.isEmpty()) {
						if(usedBy.length() > 0) usedBy.append(", ");
						int size = sendmailServers.size();
						useCount += size;
						usedBy.append(size).append(size == 1 ? " SendmailServer(Server)" : " SendmailServers(Server)");
					}
					if(!sendmailClients.isEmpty()) {
						if(usedBy.length() > 0) usedBy.append(", ");
						int size = sendmailClients.size();
						useCount += size;
						usedBy.append(size).append(size == 1 ? " SendmailServer(Client)" : " SendmailServers(Client)");
					}
					results.add(
						new Check(
							"Certificate used?",
							Integer.toString(useCount),
							useCount == 0 ? LOW : NONE,
							usedBy.length() == 0 ? "Certificate appears to be unused" : usedBy.toString()
						)
					);

					return results;
				}
			);
		} catch(InterruptedException e) {
			InterruptedIOException ioErr = new InterruptedIOException();
			ioErr.initCause(e);
			throw ioErr;
		} catch(ExecutionException e) {
			Throwable cause = e.getCause();
			if(cause instanceof IOException) throw (IOException)cause;
			throw new IOException(cause);
		}
	}

	private SslCertificateManager() {}
}
