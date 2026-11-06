package org.json;
import java.util.*;
public class JSONArray {
    final List<Object> list = new ArrayList<>();
    public JSONArray() {}
    public JSONArray put(Object o){ list.add(o); return this; }
    public int length(){ return list.size(); }
    public String optString(int i,String d){ if(i<0||i>=list.size())return d; Object o=list.get(i); return o==null?d:String.valueOf(o); }
    public JSONObject optJSONObject(int i){ if(i<0||i>=list.size())return null; Object o=list.get(i); return o instanceof JSONObject?(JSONObject)o:null; }
    public String toString(){ StringBuilder sb=new StringBuilder("["); for(int i=0;i<list.size();i++){ if(i>0)sb.append(','); sb.append(JSONObject.write(list.get(i))); } return sb.append(']').toString(); }
}
