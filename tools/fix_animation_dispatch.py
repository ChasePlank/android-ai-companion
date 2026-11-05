from pathlib import Path
import re
ROOT = Path(__file__).resolve().parents[1]
J = ROOT / 'app/src/main/java/com/chase/companion'
def once(s, old, new):
    if s.count(old) != 1:
        raise RuntimeError('UI/manual anchor changed: ' + old[:100])
    return s.replace(old, new, 1)
p = J / 'AlongsideActivity.kt'
s = p.read_text()
old = 'val ids = names.indices.filter { i -> (next in listOf("Node Control", "Defaults", "Animations", "Toggles", "Full Rig Inspection", "Character Settings") || i < 3) && testing.allowed(names[i]) }'
s = once(s, old, 'val ids = names.indices.filter { testing.allowed(names[it]) }')
old = '!names[it].startsWith("naked ")'
if s.count(old) != 3:
    raise RuntimeError('Expected home selection plus startup and navigation fallback eligibility filters.')
s = s.replace(old, 'testing.allowed(names[it])')
values = {}
for field in ('names','files'):
    matches = re.findall(r'private val ' + field + r' = listOf\(([^\n]*)\)', s)
    if len(matches) != 1:
        raise RuntimeError('Expected one complete character catalog field: ' + field)
    values[field] = matches[0]
p.write_text(s)
catalog = '''package com.chase.companion
import android.content.Context
internal object CharacterCatalog {
    val names = listOf(@NAMES@)
    val files = listOf(@FILES@)
    fun eligible(name: String, developer: Boolean, blocked: Boolean, relationship: Int): Boolean {
        return !name.startsWith("naked ") || name.removePrefix("naked ") == "Fexa" || (developer && !blocked) || relationship in 1000..1099
    }
    fun allowed(c: Context, name: String): Boolean {
        val p = c.getSharedPreferences("alongside_ui_v1", Context.MODE_PRIVATE)
        return eligible(name,p.getBoolean("dev_enabled",false),p.getBoolean("dev_access_blocked",false),p.getInt("relationship_real:${name.removePrefix("naked ")}",100))
    }
    fun visible(c: Context) = names.indices.filter { allowed(c,names[it]) }
}
'''
(J / 'CharacterCatalog.kt').write_text(catalog.replace('@NAMES@',values['names']).replace('@FILES@',values['files']))
p = J / 'TestingFeatures.kt'
s = p.read_text()
old = 'fun allowed(n: String): Boolean = !n.startsWith("naked ") || base(n) == "Fexa" || enabled() || prefs.getInt("relationship_real:${base(n)}", 100) in 1000..1099'
s = once(s, old, 'fun allowed(n: String): Boolean { if (n.startsWith("naked ")) enabled(); return CharacterCatalog.allowed(a,n) }')
s = once(s, 'player.request("manual",draft)', 'player.request("manual:" + UUID.randomUUID().toString(),draft)')
s = once(s, 'val play = { player.cancel("manual"); selected=ids[n]; restore(); playDraft() }', 'val play = { selected=ids[n]; restore(); playDraft() }')
old = 'player.cancel("manual")'
if s.count(old) != 2:
    raise RuntimeError('Expected exactly two manual cancel controls.')
s = s.replace(old, 'player.cancelPrefix("manual:")')
p.write_text(s)
p = J / 'CompanionCue.kt'
s = p.read_text()
s = once(s, 'val names = listOf("Roxanne", "Fexa", "Loona")', 'val names = CharacterCatalog.names')
s = once(s, 'val files = listOf("roxanne_wolf_security_breach.glb", "fexa_blush_alt_nude.glb", "loona.glb")', 'val files = CharacterCatalog.files')
s = once(s, 'val next = when (spoken.lowercase(Locale.ROOT)) { "roxy", "roxanne" -> 0; "fexa" -> 1; "loona" -> 2; else -> return }', '''val requested = if (spoken.equals("roxy",true)) "Roxanne" else spoken
        val next = names.indexOfFirst { it.equals(requested,true) }
        if (next < 0 || !CharacterCatalog.allowed(c,names[next])) return''')
s = once(s, 'android.app.AlertDialog.Builder(a).setTitle("Choose character").setItems(names.toTypedArray()) { _, i -> select(a, names[i]); OverlaySettings.show(a) }.setNegativeButton("Cancel", null).show()', '''val ids = CharacterCatalog.visible(a)
            android.app.AlertDialog.Builder(a).setTitle("Choose character").setItems(ids.map { names[it] }.toTypedArray()) { _, row -> select(a, names[ids[row]]); OverlaySettings.show(a) }.setNegativeButton("Cancel", null).show()''')
p.write_text(s)
p = ROOT / 'app/src/test/java/com/chase/companion/NodeAnimationPlayerTest.kt'
s = p.read_text()
test = '''    @Test fun manualRequestsOverlapAndCancelWithoutStoppingAutomaticPlayback() {
        val p = NodeAnimationPlayer({pose()},{pose()},{})
        p.request("manual:first",animation("blink"))
        p.request("manual:second",animation("head"))
        p.request("auto:rotation",animation("jaw"))
        p.tick(1000000000L)
        assertEquals(setOf("manual:first","manual:second","auto:rotation"),p.activeIds())
        p.cancelPrefix("manual:")
        assertEquals(setOf("auto:rotation"),p.activeIds())
    }
'''
if not s.rstrip().endswith('}'):
    raise RuntimeError('Test class ending changed.')
p.write_text(s.rstrip()[:-1] + test + '}\n')
(ROOT / 'app/src/test/java/com/chase/companion/CharacterCatalogTest.kt').write_text('''package com.chase.companion
import org.junit.Assert.*
import org.junit.Test
class CharacterCatalogTest {
    @Test fun fullBaseListIsAvailableWithoutUnlocks() {
        assertEquals(CharacterCatalog.names.size,CharacterCatalog.files.size)
        val visible = CharacterCatalog.names.filter { CharacterCatalog.eligible(it,false,false,100) }
        assertEquals(listOf("Roxanne","Fexa","Loona","Bonfie","Chiku","Fredina","Glamrock Chica","Diane Foxington","Marie"),visible)
    }
    @Test fun nudeVariantsKeepExistingUnlockRules() {
        for (name in CharacterCatalog.names.filter { it.startsWith("naked ") }) {
            assertFalse(CharacterCatalog.eligible(name,false,false,999))
            assertTrue(CharacterCatalog.eligible(name,false,false,1000))
            assertTrue(CharacterCatalog.eligible(name,false,false,1099))
            assertFalse(CharacterCatalog.eligible(name,false,false,1100))
            assertTrue(CharacterCatalog.eligible(name,true,false,100))
            assertFalse(CharacterCatalog.eligible(name,true,true,100))
        }
    }
}
''')
print('Full eligible character lists enabled for activity and overlay-settings pickers, with existing unlock rules. Additive manual starts and manual-only cancellation retained. Scheduler placement, animation engine, overlay idle and inference unchanged from the working pre-dispatch implementation. Build and device tests required.')
