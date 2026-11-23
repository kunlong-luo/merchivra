package org.merchivra.erp.finance;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * V209: READ-ONLY OFFLINE PKIX/CRL readiness using out-of-band
 * deployment-operator-provisioned files. Never accepts a tenant-uploaded
 * root or CRL, never fetches any URL, and NEVER asserts real issuer identity.
 * V208 Ed25519 must independently validate the exact original-document payload.
 */
@Component
public final class OfflineIssuerCertificateTrustV209 {
    static final int MAX_FILE_BYTES = 65536;
    static final Duration CRL_MAX_AGE = Duration.ofDays(7);
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    public record Verdict(String state,boolean chainValidForOperatorPinnedRoot,
        boolean offlineRevocationCurrent,String rootCertificateSha256,
        String leafPublicKeySha256,String leafNotAfter,
        boolean independentIssuerIdentityVerified) {}

    private static final String PASS =
        "OFFLINE_PKIX_AND_CRL_MATCH_TENANT_KEY_EXTERNAL_IDENTITY_UNVERIFIED";
    private final boolean enabled;
    private final String rootPath;
    private final String issuerBundlePath;
    private final String crlPath;
    private final String operatorDns;
    private final String carrierDns;
    private final Clock clock;

    @Autowired
    public OfflineIssuerCertificateTrustV209(
        @Value("${merchivra.external-issuer.offline-trust.enabled:false}") boolean enabled,
        @Value("${merchivra.external-issuer.offline-trust.root-cert-file:}") String rootPath,
        @Value("${merchivra.external-issuer.offline-trust.issuer-chain-file:}") String issuerBundlePath,
        @Value("${merchivra.external-issuer.offline-trust.crl-file:}") String crlPath,
        @Value("${merchivra.external-issuer.offline-trust.operator-dns-allowlist:}") String operatorDns,
        @Value("${merchivra.external-issuer.offline-trust.carrier-dns-allowlist:}") String carrierDns) {
        this(enabled,rootPath,issuerBundlePath,crlPath,operatorDns,carrierDns,
            Clock.systemUTC());
    }

    OfflineIssuerCertificateTrustV209(boolean enabled,String rootPath,
        String issuerBundlePath,String crlPath,String operatorDns,
        String carrierDns,Clock clock) {
        this.enabled=enabled;
        this.rootPath=rootPath==null?"":rootPath;
        this.issuerBundlePath=issuerBundlePath==null?"":issuerBundlePath;
        this.crlPath=crlPath==null?"":crlPath;
        this.operatorDns=operatorDns==null?"":operatorDns;
        this.carrierDns=carrierDns==null?"":carrierDns;
        this.clock=clock;
    }

    private static Verdict fail(String state) {
        return new Verdict(state,false,false,null,null,null,false);
    }
    private static String sha(byte[] input) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(input));
    }
    private static boolean domain(String raw) {
        return raw!=null && raw.length()<=253
            && raw.equals(raw.toLowerCase(java.util.Locale.ROOT))
            && raw.matches("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
                +"[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?");
    }
    private static boolean allowed(String kind,String claimedIssuer,
        String operatorAllow,String carrierAllow) {
        if(!domain(claimedIssuer))return false;
        String list=switch(kind) {
            case "OPERATOR_REGISTRY" -> operatorAllow;
            case "CARRIER_HANDOFF" -> carrierAllow;
            default -> "";
        };
        for(String raw:list.split(",",-1)) {
            String item=raw.strip();
            if(domain(item)&&item.equals(claimedIssuer))return true;
        }
        return false;
    }

    /** Refuse symlinks, relative paths, unexpected size and writable files. */
    private static byte[] operatorFile(String configured) throws Exception {
        if(configured==null||configured.isBlank())
            throw new IOException("Missing operator certificate material");
        Path p=Path.of(configured);
        if(!p.isAbsolute()
            ||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Operator trust file must be absolute and regular");
        long onDisk=Files.size(p);
        if(onDisk==0||onDisk>MAX_FILE_BYTES)
            throw new IOException("Operator trust file outside size limits");
        var bytes=Files.readAllBytes(p);
        if(bytes.length==0||bytes.length>MAX_FILE_BYTES)
            throw new IOException("Operator trust file outside size limits");
        try {
            var permissions=Files.getPosixFilePermissions(p,LinkOption.NOFOLLOW_LINKS);
            if(permissions.contains(PosixFilePermission.GROUP_WRITE)
                ||permissions.contains(PosixFilePermission.OTHERS_WRITE))
                throw new IOException("Operator trust file is group/world writable");
        } catch(UnsupportedOperationException ignored) {
            // Non-POSIX filesystems still require deployment ACL review.
        }
        return bytes;
    }

    private static X509Certificate root(CertificateFactory factory,
        byte[] bytes,Instant now) throws Exception {
        var certificates=factory.generateCertificates(new ByteArrayInputStream(bytes));
        if(certificates.size()!=1 || !(certificates.iterator().next() instanceof X509Certificate root))
            throw new TrustException("OPERATOR_TRUST_ROOT_INVALID");
        root.checkValidity(Date.from(now));
        if(root.getBasicConstraints()<0
            ||!root.getSubjectX500Principal().equals(root.getIssuerX500Principal()))
            throw new TrustException("OPERATOR_TRUST_ROOT_INVALID");
        boolean[] usage=root.getKeyUsage();
        if(usage==null||usage.length<=6||!usage[5]||!usage[6])
            throw new TrustException("OPERATOR_TRUST_ROOT_INVALID");
        root.verify(root.getPublicKey());
        return root;
    }

    private static boolean sanMatches(X509Certificate leaf,String expected) throws Exception {
        var names=leaf.getSubjectAlternativeNames();
        if(names==null)return false;
        for(var entry:names) {
            if(entry.size()>=2 && Integer.valueOf(2).equals(entry.get(0))
                && expected.equals(entry.get(1)))return true;
        }
        return false;
    }

    private static class TrustException extends Exception {
        final String state;
        TrustException(String state) {super(state);this.state=state;}
    }

    private static void validateCurrentCrl(List<X509Certificate> chain,
        X509Certificate root,List<X509CRL> crls,Instant now) throws Exception {
        for(int i=0;i<chain.size();i++) {
            X509Certificate subject=chain.get(i);
            X509Certificate issuing=i+1<chain.size()?chain.get(i+1):root;
            boolean currentAndSigned=false,revoked=false;
            for(X509CRL crl:crls) {
                if(!crl.getIssuerX500Principal()
                    .equals(issuing.getSubjectX500Principal()))continue;
                if(crl.getThisUpdate()==null ||crl.getNextUpdate()==null)continue;
                Instant issued=crl.getThisUpdate().toInstant();
                Instant until=crl.getNextUpdate().toInstant();
                if(issued.isAfter(now.plus(CLOCK_SKEW))
                    ||issued.isBefore(now.minus(CRL_MAX_AGE).minus(CLOCK_SKEW))
                    ||!until.isAfter(now)
                    ||until.isAfter(issued.plus(CRL_MAX_AGE).plusSeconds(1)))continue;
                try {crl.verify(issuing.getPublicKey());}
                catch(Exception invalidSignature) {continue;}
                currentAndSigned=true;
                if(crl.isRevoked(subject))revoked=true;
            }
            if(!currentAndSigned)
                throw new TrustException("SIGNED_CURRENT_CRL_MISSING_OR_STALE");
            // If ANY acceptable CRL revokes a certificate, newer/older
            // ambiguous bundles cannot silently de-revoke the historical claim.
            if(revoked)throw new TrustException("CERTIFICATE_REVOKED_IN_OPERATOR_CRL");
        }
    }

    /**
     * No caching: every call reads current operator-managed trust files,
     * including CRL. Replacing a CRL or disabling the gate changes readiness
     * immediately without rewriting V208 evidence or promoting issuer trust.
     */
    public Verdict evaluate(String kind,String claimedIssuer,String keySha256) {
        if(!enabled)return fail("OPERATOR_OFFLINE_TRUST_DISABLED");
        if(!allowed(kind,claimedIssuer,operatorDns,carrierDns))
            return fail("CLAIMED_ISSUER_NOT_OPERATOR_ALLOWLISTED");
        if(keySha256==null||!keySha256.matches("[0-9a-f]{64}"))
            return fail("V208_TENANT_KEY_FINGERPRINT_INVALID");
        try {
            Instant now=clock.instant();
            var factory=CertificateFactory.getInstance("X.509");
            X509Certificate trustedRoot=root(factory,operatorFile(rootPath),now);
            var certificates=factory.generateCertificates(
                new ByteArrayInputStream(operatorFile(issuerBundlePath)));
            var candidates=new ArrayList<X509Certificate>();
            for(var cert:certificates) {
                if(!(cert instanceof X509Certificate x509))
                    throw new TrustException("OPERATOR_CERTIFICATE_CHAIN_INVALID");
                candidates.add(x509);
            }
            X509Certificate leaf=null;
            for(var candidate:candidates) {
                if(keySha256.equals(sha(candidate.getPublicKey().getEncoded()))) {
                    if(leaf!=null)throw new TrustException("DUPLICATE_PINNED_ISSUER_CERTIFICATE");
                    leaf=candidate;
                }
            }
            if(leaf==null)return fail("TENANT_KEY_NOT_MATCHED_TO_OFFLINE_LEAF_CERT");
            leaf.checkValidity(Date.from(now));
            boolean[] ku=leaf.getKeyUsage();
            if(leaf.getBasicConstraints()>=0||ku==null||ku.length<1||!ku[0]
                ||!("EdDSA".equalsIgnoreCase(leaf.getPublicKey().getAlgorithm())
                    ||"Ed25519".equalsIgnoreCase(leaf.getPublicKey().getAlgorithm())))
                return fail("OFFLINE_ISSUER_CERTIFICATE_NOT_ED25519_SIGNING_LEAF");
            if(!sanMatches(leaf,claimedIssuer))
                return fail("CLAIMED_ISSUER_DNS_SAN_MISMATCH");
            // Construct the path ONLY from explicitly operator supplied
            // certificates; do not let a JVM-wide AIA/CRLDP/OCSP setting
            // cause hidden external HTTP requests from CertPathBuilder.
            var chain=new ArrayList<X509Certificate>();
            X509Certificate current=leaf;
            for(int depth=0;depth<=candidates.size();depth++) {
                chain.add(current);
                if(current.getIssuerX500Principal()
                    .equals(trustedRoot.getSubjectX500Principal())) {
                    current.verify(trustedRoot.getPublicKey());
                    break;
                }
                X509Certificate next=null;
                for(var candidate:candidates) {
                    if(candidate==current
                        || candidate.getBasicConstraints()<0
                        || !candidate.getSubjectX500Principal()
                            .equals(current.getIssuerX500Principal()))
                        continue;
                    try {
                        current.verify(candidate.getPublicKey());
                    } catch(Exception invalidIssuer) {
                        continue;
                    }
                    if(next!=null)
                        throw new TrustException("AMBIGUOUS_OPERATOR_CERTIFICATE_CHAIN");
                    next=candidate;
                }
                if(next==null||chain.contains(next)
                    ||depth==candidates.size())
                    throw new TrustException("OPERATOR_CERTIFICATE_CHAIN_INVALID");
                current=next;
            }
            var options=new PKIXParameters(
                Set.of(new TrustAnchor(trustedRoot,null)));
            options.setDate(Date.from(now));
            options.setRevocationEnabled(false);
            CertPathValidator.getInstance("PKIX").validate(
                factory.generateCertPath(chain),options);
            var sourceCrls=factory.generateCRLs(
                new ByteArrayInputStream(operatorFile(crlPath)));
            var crls=new ArrayList<X509CRL>();
            for(var crl:sourceCrls) {
                if(!(crl instanceof X509CRL x509crl))
                    throw new TrustException("SIGNED_CURRENT_CRL_MISSING_OR_STALE");
                crls.add(x509crl);
            }
            validateCurrentCrl(chain,trustedRoot,crls,now);
            return new Verdict(PASS,true,true,sha(trustedRoot.getEncoded()),
                keySha256,leaf.getNotAfter().toInstant().toString(),false);
        } catch(TrustException invalid) {
            return fail(invalid.state);
        } catch(Exception unavailable) {
            // No stack traces, filenames, certificate subject names, or local
            // file path are reflected to the client.
            return fail("OFFLINE_CERTIFICATE_CHAIN_OR_TRUST_MATERIAL_NOT_VERIFIABLE");
        }
    }
}
