package com.chase.companion
import org.json.JSONObject
import org.json.JSONArray
internal class NodeAnimationPlayer(private val capture: () -> JSONObject,private val defaults: () -> JSONObject,private val apply: (JSONObject) -> Unit) {
    private data class Pending(val id: String,val animation: JSONObject,val nodes: Set<String>)
    private data class Active(val request: Pending,val initial: JSONObject,val frames: List<JSONObject>,val speed: Double,var last: Long,var elapsed: Double=0.0)
    private val queue=mutableListOf<Pending>()
    private val active=mutableListOf<Active>()
    fun activeIds()=active.map { it.request.id }.toSet()
    fun contains(id: String)=queue.any { it.id==id } || active.any { it.request.id==id }
    fun running()=active.isNotEmpty() || queue.isNotEmpty()
    fun owns(node: String)=active.any { node in it.request.nodes }
    fun infer(animation: JSONObject): Set<String> {
        animation.optJSONArray("owned")?.let { a -> return (0 until a.length()).map { a.getString(it) }.toSet() }
        val result=linkedSetOf<String>(); val rest=defaults(); val poses=animation.optJSONArray("poses") ?: JSONArray()
        for(i in 0 until poses.length()) {
            val p=poses.getJSONObject(i).getJSONObject("pose"); val nodes=p.optJSONObject("nodes") ?: JSONObject(); val base=rest.getJSONObject("nodes")
            nodes.keys().forEach { k -> val a=nodes.optJSONArray(k); val b=base.optJSONArray(k); if(a!=null && (b==null || (0 until a.length()).any { j -> kotlin.math.abs(a.optDouble(j)-b.optDouble(j))>0.0001 })) result.add(k) }
            if(p.has("camera") && p.optJSONObject("camera").toString()!=rest.optJSONObject("camera").toString()) result.add("@camera")
            if(p.has("orientation") && p.optJSONArray("orientation").toString()!=rest.optJSONArray("orientation").toString()) result.add("@orientation")
        }
        return result
    }
    fun sparse(pose: JSONObject,nodes: Set<String>): JSONObject {
        val result=JSONObject(); val values=JSONObject(); val actual=pose.optJSONObject("nodes") ?: JSONObject(); val rest=defaults().getJSONObject("nodes")
        nodes.filter { !it.startsWith("@") }.forEach { k -> val v=actual.optJSONArray(k) ?: rest.optJSONArray(k); if(v!=null) values.put(k,JSONArray(v.toString())) }; result.put("nodes",values)
        if("@camera" in nodes) result.put("camera",JSONObject((pose.optJSONObject("camera") ?: defaults().getJSONObject("camera")).toString()))
        if("@orientation" in nodes) result.put("orientation",JSONArray((pose.optJSONArray("orientation") ?: defaults().getJSONArray("orientation")).toString()))
        return result
    }
    fun request(id: String,animation: JSONObject): Boolean { if(contains(id)) return false; val data=JSONObject(animation.toString()); val nodes=infer(data); if(nodes.isEmpty()) return false; queue.add(Pending(id,data,nodes)); return true }
    fun cancel(id: String) { active.filter { it.request.id==id }.toList().forEach { apply(sparse(defaults(),it.request.nodes)); active.remove(it) }; queue.removeAll { it.id==id } }
    fun cancelAll() { active.toList().forEach { apply(sparse(defaults(),it.request.nodes)) }; active.clear(); queue.clear() }
    fun cancelPrefix(prefix: String) { active.filter { it.request.id.startsWith(prefix) }.toList().forEach { apply(sparse(defaults(),it.request.nodes)); active.remove(it) }; queue.removeAll { it.id.startsWith(prefix) } }
    private fun blend(x: JSONObject,y: JSONObject,t: Double): JSONObject {
        val out=JSONObject(); y.keys().forEach { k -> val a=x.opt(k); val b=y.get(k); val v: Any=when { a is JSONObject && b is JSONObject -> blend(a,b,t); a is JSONArray && b is JSONArray && a.length()==b.length() -> JSONArray((0 until b.length()).map { j -> a.getDouble(j)+(b.getDouble(j)-a.getDouble(j))*t }); a is Number && b is Number && k!="version" -> a.toDouble()+(b.toDouble()-a.toDouble())*t; else -> b }; out.put(k,v) }; return out
    }
    fun tick(time: Long) {
        for(run in active.toList()) {
            run.elapsed+=(time-run.last).coerceAtLeast(0L)/1e9*run.speed; run.last=time; var left=run.elapsed; var from=run.initial; var applied=false
            for(frame in run.frames) {
                val move=frame.optDouble("move",1.0).coerceAtLeast(0.0)/frame.optDouble("local",1.0).coerceIn(0.25,4.0); val hold=frame.optDouble("hold",0.0).coerceAtLeast(0.0); val to=frame.getJSONObject("pose")
                if(left<move) { val t=(left/move).coerceIn(0.0,1.0); apply(blend(from,to,t*t*(3-2*t))); applied=true; break }; left-=move
                if(left<hold) { apply(to); applied=true; break }; left-=hold; from=to
            }
            if(!applied) { apply(from); active.remove(run) }
        }
        for(pending in queue.toList()) {
            if(active.any { it.request.nodes.any { n -> n in pending.nodes } }) continue
            val endpoint=sparse(defaults(),pending.nodes)
            fun end()=JSONObject().put("pose",JSONObject(endpoint.toString())).put("move",1.0).put("hold",0.0).put("local",1.0)
            val frames=mutableListOf(end()); val poses=pending.animation.optJSONArray("poses") ?: JSONArray()
            for(i in 0 until poses.length()) { val f=JSONObject(poses.getJSONObject(i).toString()); if(!f.optBoolean("locked",false)) { f.put("pose",sparse(f.getJSONObject("pose"),pending.nodes)); frames.add(f) } }
            frames.add(end()); active.add(Active(pending,sparse(capture(),pending.nodes),frames,pending.animation.optDouble("speed",1.0).coerceIn(0.25,4.0),time)); queue.remove(pending)
        }
    }
}
