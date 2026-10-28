package com.chase.companion;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read-only inspection of the packaged GLB; no Filament engine or model edits. */
public class KeytarInspectionActivity extends Activity {
 private volatile boolean closed;
 private String report;
 private TextView text;
 private Button copy;
 @Override public void onCreate(Bundle saved) {
  super.onCreate(saved);
  LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setPadding(20,48,20,24);
  TextView title=new TextView(this); title.setText("Keytar mesh inspector"); title.setTextSize(22); root.addView(title);
  TextView help=new TextView(this); help.setText("Read-only diagnostic. Wait for the report, tap Copy report, then paste it into the chat. Nothing is hidden or moved by this screen."); root.addView(help);
  copy=new Button(this); copy.setText("Copy report"); copy.setEnabled(false);
  copy.setOnClickListener(v -> { if(report!=null) { ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE); clipboard.setPrimaryClip(ClipData.newPlainText("Keytar inspection",report)); Toast.makeText(this,"Report copied",Toast.LENGTH_SHORT).show(); } });
  root.addView(copy);
  Button preview=new Button(this); preview.setText("Open rig preview"); preview.setOnClickListener(v -> startActivity(new Intent(this,AvatarPreviewActivity.class))); root.addView(preview);
  text=new TextView(this); text.setTextIsSelectable(true); text.setText("Reading bundled model and checking skin influences…");
  ScrollView scroll=new ScrollView(this); scroll.addView(text); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);
  new Thread(() -> {
   String result;
   try {
    byte[] bytes;
    try(InputStream input=getAssets().open("roxanne_wolf_security_breach.glb"); ByteArrayOutputStream output=new ByteArrayOutputStream()) {
     byte[] buffer=new byte[65536]; int count;
     while((count=input.read(buffer))!=-1) { output.write(buffer,0,count); if(output.size()>67108864) throw new IOException("Model exceeds 64 MiB inspection limit"); }
     bytes=output.toByteArray();
    }
    result=new Inspector(bytes).inspect();
   } catch(Exception error) { result="Inspection failed: "+error.getClass().getSimpleName()+": "+error.getMessage(); }
   final String complete=result;
   runOnUiThread(() -> { if(!closed) { report=complete; text.setText(complete); copy.setEnabled(true); } });
  },"inspect-keytar-meshes").start();
 }
 @Override protected void onDestroy() { closed=true; super.onDestroy(); }

 private static final class Inspector {
  final ByteBuffer data;
  final JSONObject json;
  final JSONArray nodes,meshes,skins,accessors,views;
  final Set<Integer> propNodes=new TreeSet<>();
  int binStart=-1,binLength;
  Inspector(byte[] bytes) throws Exception {
   data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
   if(bytes.length<20 || data.getInt()!=0x46546c67 || data.getInt()!=2 || data.getInt()!=bytes.length) throw new IOException("Invalid GLB v2 header");
   JSONObject parsed=null;
   while(data.remaining()>=8) {
    int length=data.getInt(),type=data.getInt(),start=data.position();
    if(length<0 || length>data.remaining()) throw new IOException("Invalid chunk length");
    if(type==0x4e4f534a) parsed=new JSONObject(new String(bytes,start,length,StandardCharsets.UTF_8).replace("\u0000","").trim());
    if(type==0x004e4942) { binStart=start; binLength=length; }
    data.position(start+length);
   }
   if(parsed==null) throw new IOException("GLB JSON missing");
   json=parsed; nodes=array(json,"nodes"); meshes=array(json,"meshes"); skins=array(json,"skins"); accessors=array(json,"accessors"); views=array(json,"bufferViews");
   for(int i=0;i<nodes.length();i++) if("World_Prop_jnt_073".equals(nodes.getJSONObject(i).optString("name"))) collect(i);
  }
  static JSONArray array(JSONObject object,String key) { JSONArray result=object.optJSONArray(key); return result==null?new JSONArray():result; }
  void collect(int index) throws JSONException { if(!propNodes.add(index)) return; JSONArray children=array(nodes.getJSONObject(index),"children"); for(int i=0;i<children.length();i++) collect(children.getInt(i)); }
  String name(int index) throws JSONException { return index+":"+nodes.getJSONObject(index).optString("name","(unnamed)"); }
  String inspect() throws Exception {
   StringBuilder out=new StringBuilder("KEYTAR INSPECTION v1\nNo visibility or pose changes applied.\n");
   out.append("Nodes: ").append(nodes.length()).append("; meshes: ").append(meshes.length()).append("; skins: ").append(skins.length()).append('\n');
   out.append("World_Prop_jnt_073 subtree: "); for(int node:propNodes) out.append(name(node)).append("; "); out.append('\n');
   if(propNodes.isEmpty()) out.append("WARNING: exact prop node not found.\n");
   out.append("\nMESH NODE INFLUENCES\nCounts are per primitive POSITION vertex, including unreferenced vertices.\n");
   for(int n=0;n<nodes.length();n++) {
    JSONObject node=nodes.getJSONObject(n); int meshIndex=node.optInt("mesh",-1); if(meshIndex<0) continue;
    JSONObject mesh=meshes.getJSONObject(meshIndex); int skin=node.optInt("skin",-1);
    out.append("\nNode ").append(name(n)).append("; mesh ").append(meshIndex).append(":").append(mesh.optString("name","(unnamed)")).append("; skin=").append(skin).append("; in prop hierarchy=").append(propNodes.contains(n)).append('\n');
    JSONArray primitives=array(mesh,"primitives");
    for(int p=0;p<primitives.length();p++) {
     JSONObject primitive=primitives.getJSONObject(p); out.append(" Primitive ").append(p).append(" material=").append(primitive.optInt("material",-1)).append(": ");
     try { out.append(influences(primitive,skin)); } catch(Exception error) { out.append("UNRESOLVED: ").append(error.getMessage()); }
     out.append('\n');
    }
   }
   out.append("\nNODE HIERARCHY\n");
   int[] parents=new int[nodes.length()]; Arrays.fill(parents,-1);
   for(int n=0;n<nodes.length();n++) { JSONArray children=array(nodes.getJSONObject(n),"children"); for(int j=0;j<children.length();j++) parents[children.getInt(j)]=n; }
   for(int n=0;n<nodes.length();n++) { JSONObject node=nodes.getJSONObject(n); out.append(name(n)).append(" parent=").append(parents[n]).append(" mesh=").append(node.optInt("mesh",-1)).append(" skin=").append(node.optInt("skin",-1)).append('\n'); }
   return out.toString();
  }
  String influences(JSONObject primitive,int skin) throws Exception {
   if(skin<0) return "Unskinned; inspect mesh-node ancestry.";
   JSONObject attributes=primitive.getJSONObject("attributes");
   JSONObject extensions=primitive.optJSONObject("extensions");
   if(extensions!=null && extensions.has("KHR_draco_mesh_compression")) throw new IOException("Draco compression requires decoded inspection");
   JSONArray skinJoints=skins.getJSONObject(skin).getJSONArray("joints");
   ArrayList<String> sets=new ArrayList<>(); Iterator<String> keys=attributes.keys();
   while(keys.hasNext()) { String key=keys.next(); if(key.startsWith("JOINTS_")) sets.add(key.substring(7)); }
   Collections.sort(sets); if(sets.isEmpty()) throw new IOException("Skinned mesh has no JOINTS attributes");
   int count=accessors.getJSONObject(attributes.getInt("POSITION")).getInt("count");
   int propOnly=0,mixed=0,otherOnly=0,unweighted=0; Set<Integer> used=new TreeSet<>();
   for(int vertex=0;vertex<count;vertex++) {
    boolean prop=false,other=false;
    for(String set:sets) {
     int jointAccessor=attributes.getInt("JOINTS_"+set),weightAccessor=attributes.getInt("WEIGHTS_"+set);
     for(int c=0;c<4;c++) {
      double weight=number(weightAccessor,vertex,c); if(!Double.isFinite(weight) || weight<0) throw new IOException("Invalid weight"); if(weight==0) continue;
      int slot=(int)number(jointAccessor,vertex,c); int joint=skinJoints.getInt(slot); used.add(joint);
      if(propNodes.contains(joint)) prop=true; else other=true;
     }
    }
    if(prop && other) mixed++; else if(prop) propOnly++; else if(other) otherOnly++; else unweighted++;
   }
   StringBuilder result=new StringBuilder(); result.append("vertices=").append(count).append(", prop-only=").append(propOnly).append(", mixed=").append(mixed).append(", other-only=").append(otherOnly).append(", unweighted=").append(unweighted).append("; used joints: ");
   for(int joint:used) result.append(name(joint)).append("; ");
   return result.toString();
  }
  double number(int accessorIndex,int vertex,int component) throws Exception {
   JSONObject accessor=accessors.getJSONObject(accessorIndex);
   if(accessor.has("sparse")) throw new IOException("Sparse accessor requires decoded inspection");
   if(!"VEC4".equals(accessor.getString("type")) || vertex>=accessor.getInt("count")) throw new IOException("Unexpected skin accessor shape");
   JSONObject view=views.getJSONObject(accessor.getInt("bufferView"));
   if(view.optInt("buffer",0)!=0 || binStart<0 || view.optJSONObject("extensions")!=null) throw new IOException("External or extended buffer view");
   int type=accessor.getInt("componentType"),size;
   switch(type) { case 5121: size=1; break; case 5123: size=2; break; case 5126: size=4; break; default: throw new IOException("Unsupported skin component type "+type); }
   int stride=view.optInt("byteStride",4*size); if(stride<4*size) throw new IOException("Invalid stride");
   long relative=(long)accessor.optInt("byteOffset",0)+(long)vertex*stride+(long)component*size;
   long offset=(long)view.optInt("byteOffset",0)+relative;
   if(relative<0 || relative+size>view.getInt("byteLength") || offset<0 || offset+size>binLength) throw new IOException("Accessor outside buffer bounds");
   int absolute=binStart+(int)offset; boolean normalized=accessor.optBoolean("normalized",false);
   if(type==5126) return data.getFloat(absolute);
   if(type==5123) { int value=data.getShort(absolute)&65535; return normalized?value/65535.0:value; }
   int value=data.get(absolute)&255; return normalized?value/255.0:value;
  }
 }
}
