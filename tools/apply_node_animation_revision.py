from pathlib import Path
import shutil,re
R=Path(__file__).resolve().parents[1]
J=R/'app/src/main/java/com/chase/companion'
def one(s,a,b):
    if s.count(a)!=1: raise RuntimeError('Source changed: '+a[:80])
    return s.replace(a,b,1)
p=J/'AlongsideActivity.kt'; s=p.read_text()
s=one(s,'if (testing.tick(time)) return','testing.tick(time, clip < 0 && page in listOf("Full Rig Inspection","Chat","Animations"))')
s=one(s,'poses.forEach { (key, xyz) -> applyNode(key, xyz) }','poses.forEach { (key, xyz) -> if (!testing.owns(key)) applyNode(key, xyz) }')
s=one(s,'testing.poseEdited(); val b = bounds(selectedNode, axis);','testing.nodeEdited(selectedNode); val b = bounds(selectedNode, axis);')
s=one(s,'testing.poseEdited(); v.onTouch(view, e)','testing.nodeEdited("@camera"); v.onTouch(view, e)')
s=one(s,'restoreNodes(only = selectedNode); nodeSync?.invoke()','if (page == "Animation Editor") testing.nodeEdited(selectedNode); restoreNodes(only = selectedNode); nodeSync?.invoke()')
a=s.index('    private fun applyTesting(pose: JSONObject) {'); b=s.index('    private fun dp(v: Int)',a)
s=s[:a]+'''    private fun applyTesting(pose: JSONObject) {
        val nodes=pose.optJSONObject("nodes") ?: JSONObject()
        nodes.keys().forEach { k -> if(k in offsets) { val xyz=vector(nodes.optJSONArray(k)); for(i in 0..2) { val b=bounds(k,i); xyz[i]=xyz[i].coerceIn(b.first,b.second) }; offsets[k]=xyz; applyNode(k,xyz) } }
        if(pose.has("orientation")) { orientation=vector(pose.optJSONArray("orientation")); applyOrientation() }
        if(pose.has("camera")) orbit?.let { PreviewCameraDefaults.restore(it,pose.optJSONObject("camera")) }
        viewer?.animator?.updateBoneMatrices(); nodeSync?.invoke()
    }
'''+s[b:]
home='files.indexOf(appPrefs.getString("home_default_file",files[0])).takeIf { it in files.indices && !names[it].startsWith("naked ") } ?: 0'
s=one(s,'character = intent.getIntExtra("character", 0).takeIf { it in files.indices } ?: 0','character = if(intent.hasExtra("character")) intent.getIntExtra("character",0).takeIf { it in files.indices } ?: 0 else '+home)
s=one(s,'"Full Rig Inspection" -> chooseCharacter(0, next)','"Full Rig Inspection" -> chooseCharacter('+home+', next)')
s=one(s,'testing.settings(panel) { enforceVariant() };','''testing.settings(panel) { enforceVariant() };
        button(panel,"Default home model") { val ids=names.indices.filter { !names[it].startsWith("naked ") }; AlertDialog.Builder(this).setTitle("Default home model").setItems(ids.map { names[it] }.toTypedArray()) { _, n -> appPrefs.edit().putString("home_default_file",files[ids[n]]).apply(); status.text="Home model: ${names[ids[n]]}" }.setNegativeButton("Cancel",null).show() }'''); p.write_text(s)
p=J/'TestingFeatures.kt'; s=p.read_text()
s=one(s,'    private var key = ""','''    private val player by lazy { NodeAnimationPlayer(capture,defaults,apply) }
    private var schedule=""
    private val due=mutableMapOf<String,Double>()
    private val waiting=mutableSetOf<String>()
    private val order=mutableMapOf<String,Int>()
    private var key = ""''')
s=one(s,'    fun settings(p: LinearLayout, changed: () -> Unit) {','    fun settings(p: LinearLayout, changed: () -> Unit) {\n        load(); button(p,"Default animations · ${character()}") { schedules() }')
s=one(s,'private fun commitPose() { ensurePose(); draft.getJSONArray("poses").getJSONObject(cursor).put("pose", capture()); dirty = true }','private fun commitPose() { ensurePose(); ownership(); draft.getJSONArray("poses").getJSONObject(cursor).put("pose",player.sparse(capture(),owners())); dirty=true }')
a=s.index('    private fun playDraft() {'); b=s.index('    fun libraryPage(',a)
s=s[:a]+'''    private fun playDraft() { ownership(); check(owners().isNotEmpty()) { "Use at least one node." }; player.request("manual",draft) }
'''+s[b:]
s=one(s,'pair(p, "Set pose", "Copy pose", { stop(); commitPose(); refresh() }, {\n            stop(); commitPose(); poses.put(copy(item)); cursor = poses.length() - 1; dirty = true; apply(poses.getJSONObject(cursor).getJSONObject("pose")); refresh()\n        })','''pair(p,"Set pose","Copy pose",{ stop(); commitPose(); Toast.makeText(a,"Pose ${cursor+1} set",Toast.LENGTH_SHORT).show(); refresh() },{
            stop(); commitPose(); AlertDialog.Builder(a).setTitle("Copy which pose?").setItems((0 until poses.length()).map { "Pose ${it+1}" }.toTypedArray()) { _, n ->
                val added=copy(poses.getJSONObject(n)); val list=JSONArray(); for(i in 0 until poses.length()) { list.put(copy(poses.getJSONObject(i))); if(i==cursor) list.put(added) }; draft.put("poses",list); cursor++; dirty=true; apply(added.getJSONObject("pose")); refresh()
            }.setNegativeButton("Cancel",null).show()
        })
        button(p,"Delete pose") { AlertDialog.Builder(a).setTitle("Delete pose ${cursor+1}?").setMessage("Default endpoints stay protected. Node links are retained.").setNegativeButton("Cancel",null).setPositiveButton("Delete") { _, _ -> stop(); poses.remove(cursor); ensurePose(); dirty=true; apply(draft.getJSONArray("poses").getJSONObject(cursor).getJSONObject("pose")); refresh() }.show() }''')
s=one(s,'private fun entry(pose: JSONObject, locked: Boolean = false) = JSONObject().put("pose", copy(pose))','private fun entry(pose: JSONObject, locked: Boolean = false) = JSONObject().put("pose",if(locked) copy(pose) else player.sparse(pose,owners()))')
s=one(s,'val stored = copy(draft); val poses = JSONArray().put(entry(defaults(), true));','ownership(); val stored=copy(draft); val endpoint=player.sparse(defaults(),owners()); val poses=JSONArray().put(entry(endpoint,true));')
s=one(s,'for (i in 0 until editable.length()) poses.put(copy(editable.getJSONObject(i)));','for(i in 0 until editable.length()) { val f=copy(editable.getJSONObject(i)); f.put("pose",player.sparse(f.getJSONObject("pose"),owners())); poses.put(f) };')
s=one(s,'poses.put(entry(defaults(), true)); stored.put("poses", poses);','poses.put(entry(endpoint,true)); stored.put("poses",poses);')
s=one(s,'val play = { stop(); selected = ids[n]; restore(); playDraft() }','val play = { player.cancel("manual"); selected=ids[n]; restore(); playDraft() }')
if s.count('}, { stop(true) })')!=2: raise RuntimeError('Cancel control count changed')
s=s.replace('}, { stop(true) })','}, { player.cancel("manual") })')
s=one(s,'fun running() = sequence != null','fun running() = player.running()')
s=one(s,'fun stop(force: Boolean = false) { val reset = force || sequence != null; sequence = null; last = 0L; if (reset) apply(defaults()) }','fun stop(force: Boolean = false) { player.cancelAll() }')
s=one(s,'fun finish() { initial = capture(); sequence = listOf(JSONObject().put("pose", defaults()).put("move", 0.5).put("hold", 0.0)); multiplier = speed(); last = 0L; elapsed = 0.0 }','fun finish() { player.request("embedded-reset",fresh().put("owned",JSONArray(capture().getJSONObject("nodes").keys().asSequence().toList()+listOf("@camera","@orientation")))) }')
a=s.index('    fun tick(time: Long): Boolean {')
s=s[:a]+(R/'tools/node_animation_helpers.kt.txt').read_text()
p.write_text(s)
assets=R/'app/src/main/assets/characters'; assets.mkdir(parents=True,exist_ok=True)
for n in ['Roxy_details.txt','CHARACTER_RELATIONSHIP_CLARIFICATIONS.md']: shutil.copyfile(R/n,assets/n)
p=J/'OfflineAi.kt'; s=p.read_text()
s=one(s,'append("<|im_start|>system\\nYou are ").append(content(name)).append(", a personal companion. Be concise and honest. Never claim phone actions you cannot perform.<|im_end|>\\n")','append("<|im_start|>system\\n").append(content(CharacterPersona.prompt(a,name))).append("<|im_end|>\\n")'); p.write_text(s)
p=J/'HandsFreeActivity.kt'; s=p.read_text()
s=one(s,'"You are ${CompanionCue.name}, a friendly personal AI companion. Answer concisely and honestly. You cannot see the screen or control the phone. Do not claim to have performed actions."','CharacterPersona.prompt(this,CompanionCue.name)'); p.write_text(s)
p=R/'app/build.gradle'; s=p.read_text(); s=one(s,'dependencies {',"dependencies {\n testImplementation 'junit:junit:4.13.2'\n testImplementation 'org.json:json:20240303'")
s+="\ntasks.named('assembleDebug').configure { dependsOn('testDebugUnitTest') }\n"; p.write_text(s)
print('Activated node-owned animations, editor fixes, scheduling/home settings, personality and unit tests. Wave excluded; selected voice and AI inference parameters unchanged; Android character-voice integration outstanding.')
