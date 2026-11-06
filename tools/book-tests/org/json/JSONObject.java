package org.json;
import java.util.*;
public class JSONObject {
    final Map<String,Object> map = new LinkedHashMap<>();
    public JSONObject() {}
    public JSONObject(String s) throws JSONException { Parser p=new Parser(s); Object o=p.value(); p.ws(); if(p.i!=s.length()) throw new JSONException("trailing"); if(!(o instanceof JSONObject)) throw new JSONException("not object"); map.putAll(((JSONObject)o).map); }
    public JSONObject put(String k,Object v) throws JSONException { map.put(k,v); return this; }
    public JSONObject put(String k,int v) throws JSONException { map.put(k,v); return this; }
    public JSONObject put(String k,boolean v) throws JSONException { map.put(k,v); return this; }
    public boolean has(String k){ return map.containsKey(k); }
    public boolean optBoolean(String k,boolean d){ Object o=map.get(k); return o instanceof Boolean?(Boolean)o:d; }
    public String optString(String k,String d){ Object o=map.get(k); return o==null?d:String.valueOf(o); }
    public JSONArray optJSONArray(String k){ Object o=map.get(k); return o instanceof JSONArray?(JSONArray)o:null; }
    public String toString(){ return write(this); }
    static String write(Object o){
        if(o==null) return "null";
        if(o instanceof JSONObject){ StringBuilder sb=new StringBuilder("{"); boolean f=true; for(Map.Entry<String,Object> e:((JSONObject)o).map.entrySet()){ if(!f)sb.append(','); f=false; sb.append(q(e.getKey())).append(':').append(write(e.getValue())); } return sb.append('}').toString(); }
        if(o instanceof JSONArray) return o.toString();
        if(o instanceof String) return q((String)o);
        return String.valueOf(o);
    }
    static String q(String s){ StringBuilder sb=new StringBuilder("\""); for(char c: s.toCharArray()){ switch(c){ case '"': sb.append("\\\""); break; case '\\': sb.append("\\\\"); break; case '\n': sb.append("\\n"); break; case '\r': sb.append("\\r"); break; case '\t': sb.append("\\t"); break; default: if(c<0x20) sb.append(String.format("\\u%04x",(int)c)); else sb.append(c);} } return sb.append('"').toString(); }
    static class Parser {
        final String s; int i=0; Parser(String s){this.s=s;}
        void ws(){ while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++; }
        Object value() throws JSONException {
            ws(); if(i>=s.length()) throw new JSONException("eof"); char c=s.charAt(i);
            if(c=='{'){ i++; JSONObject o=new JSONObject(); ws(); if(s.charAt(i)=='}'){i++;return o;} while(true){ ws(); String k=str(); ws(); expect(':'); Object v=value(); o.map.put(k,v); ws(); if(s.charAt(i)==','){i++;continue;} expect('}'); return o; } }
            if(c=='['){ i++; JSONArray a=new JSONArray(); ws(); if(s.charAt(i)==']'){i++;return a;} while(true){ a.list.add(value()); ws(); if(s.charAt(i)==','){i++;continue;} expect(']'); return a; } }
            if(c=='"') return str();
            if(s.startsWith("true",i)){i+=4;return true;} if(s.startsWith("false",i)){i+=5;return false;} if(s.startsWith("null",i)){i+=4;return null;}
            int st=i; while(i<s.length()&&"+-0123456789.eE".indexOf(s.charAt(i))>=0)i++; if(st==i) throw new JSONException("bad"); String n=s.substring(st,i); try{ return Integer.parseInt(n);}catch(Exception e){ return Double.parseDouble(n);} }
        void expect(char c) throws JSONException { if(i>=s.length()||s.charAt(i)!=c) throw new JSONException("expected "+c); i++; }
        String str() throws JSONException { expect('"'); StringBuilder sb=new StringBuilder(); while(true){ if(i>=s.length()) throw new JSONException("unterminated"); char c=s.charAt(i++); if(c=='"')break; if(c=='\\'){ char e=s.charAt(i++); switch(e){ case 'n': sb.append('\n');break; case 'r': sb.append('\r');break; case 't': sb.append('\t');break; case 'u': sb.append((char)Integer.parseInt(s.substring(i,i+4),16)); i+=4; break; default: sb.append(e);} } else sb.append(c);} return sb.toString(); }
    }
}
