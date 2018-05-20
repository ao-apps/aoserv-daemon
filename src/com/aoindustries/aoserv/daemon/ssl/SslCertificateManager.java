/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.ssl;

import com.aoindustries.aoserv.client.AlertLevel;
import com.aoindustries.aoserv.client.SslCertificate;
import java.util.Collections;
import java.util.List;

final public class SslCertificateManager {

	public static List<SslCertificate.Check> checkSslCertificate(SslCertificate certificate) {
		// TODO: Use concurrency limiter
		// TODO: Use caches when underlying files haven't changed

		// https://support.asperasoft.com/hc/en-us/articles/216128468-OpenSSL-commands-to-check-and-verify-your-SSL-certificate-key-and-CSR
		// https://www.sslshopper.com/certificate-key-matcher.html
		// Check certificate: openssl x509 -in server.crt -text -noout
		// Check csr: openssl req -text -noout -verify -in server.csr
		// Check key and certificate match:
		//     openssl x509 -noout -modulus -in server.crt
		//     openssl rsa -noout -modulus -in server.key
		//     TODO: CSR matches key
		// cyrus and sendmail copies match (for let's encrypt) (mysql, postgresql later)
		// cyrus and sendmail permissions as expected (possibly on copy) (mysql, postgresql later)

		// Configuration:
		// DNS
		// Hostnames match what is used by (case-insensitive match)
		// No extra hostnames - low level warning if any found

		// common name is exactly as expected (case-sensitive) - low if matches case-insensitive
		// subject alt names are as expected (case-sensitive) - low if matches case-insensitive

		// Expiration date
		// Let's Encrypt: low: 12 days, medium 10 days, high 7 days, critical expired
		// Others:        low: 30 days, medium 14 days, high 7 days, critical expired

		// Self-signed as low
		// Untrusted by openssl as high

		// TODO: Certificate max alert level setting? (with an "until" date?)

		// Let's Encrypt certificate status
		return Collections.singletonList(
			new SslCertificate.Check("TODO", "Not Implemented", AlertLevel.MEDIUM)
		);
	}

	private SslCertificateManager() {}
}
