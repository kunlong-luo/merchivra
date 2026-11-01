package org.merchivra.erp.ads;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Ads-only LwA consent URL preparation; not wired to any HTTP controller.
 * A future opt-in implementation MUST persist one-time hashed state and PKCE
 * verifier server-side, bind it to a tenant/actor/profile and securely
 * exchange/store Ads tokens in a dedicated vault before exposing this URI.
 * Seller OAuth credentials and seller authorization must never be reused.
 */
public final class AdsConsentProtocol {
    private AdsConsentProtocol(){}
    static String endpoint(String region){
        return switch(region){
            case "NA" -> "https://www.amazon.com/ap/oa";
            case "EU" -> "https://eu.account.amazon.com/ap/oa";
            case "FE" -> "https://apac.account.amazon.com/ap/oa";
            default -> throw new IllegalArgumentException("Unsupported Ads consent region");
        };
    }
    public static URI prepare(String region,String clientId,String callback,
                              String state,String verifier){
        if(clientId==null||!clientId.matches("amzn1\\.application-oa2-client\\.[A-Za-z0-9_-]{8,160}"))
            throw new IllegalArgumentException("Ads-only client ID is required");
        URI redirect=URI.create(callback);
        if(!"https".equals(redirect.getScheme())||redirect.getHost()==null||
          redirect.getUserInfo()!=null||redirect.getFragment()!=null)
            throw new IllegalArgumentException("Ads redirect must be registered HTTPS");
        if(state==null||!state.matches("[A-Za-z0-9_-]{32,128}")||
          verifier==null||!verifier.matches("[A-Za-z0-9._~\\-]{43,128}"))
            throw new IllegalArgumentException("Invalid OAuth state or PKCE verifier");
        String challenge;
        try{
            challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        return URI.create(endpoint(region)+"?client_id="+enc(clientId)+
            "&scope="+enc("advertising::campaign_management")+
            "&response_type=code&redirect_uri="+enc(callback)+
            "&state="+enc(state)+"&code_challenge="+enc(challenge)+
            "&code_challenge_method=S256");
    }
    private static String enc(String v){return URLEncoder.encode(v,StandardCharsets.UTF_8);}
}
