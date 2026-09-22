package dev.openfusion.launcher;

import org.json.*;

/** Public OFAPI status. Missing/invalid counts must never be shown as zero. */
public final class ServerStatus {
    private ServerStatus() {}
    public static Integer playerCount(JSONObject response) {
        Object value=response.opt("player_count");
        if(!(value instanceof Number))return null;
        double n=((Number)value).doubleValue();
        return Double.isNaN(n)||Double.isInfinite(n)||n<0||n>Integer.MAX_VALUE||n!=Math.floor(n)?null:(int)n;
    }
    public static JSONObject fetch(String endpoint) throws Exception {
        JSONObject result=new JSONObject().put("reachable",false).put("player_count",JSONObject.NULL);
        if(endpoint==null)return result.put("direct",true);
        // A valid /status response is sufficient to establish API reachability.
        try{
            JSONObject status=HttpApi.getPublic(endpoint+"/status");
            Integer count=playerCount(status);
            result.put("reachable",true).put("player_count",count==null?JSONObject.NULL:count);
        }catch(Exception unavailable){
            // Some OFAPI servers do not expose population. Keep server status
            // independent from the availability of a player count.
            try{JSONObject info=HttpApi.getPublic(endpoint);result.put("reachable",true).put("server_name",info.optString("server_name","Server online"));}
            catch(Exception offline){return result;}
        }
        return result;
    }
}
