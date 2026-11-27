package ae.fieldguide

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONArray

internal data class Decision(
    val id: String, val prompt: String, val choices: List<String>, val correct: Int,
    val guide: String, val step: Int, val heading: String, val explanation: String,
)

internal data class FieldScenario(
    val id: String, val title: String, val tasks: List<Pair<String, String>>, val questions: List<Decision>,
)

internal fun Manual.readScenarios(json: JSONArray): List<FieldScenario> = List(json.length()) { i ->
    val scenario = json.getJSONObject(i)
    val items = scenario.getJSONObject("tasks")
    val questions = scenario.getJSONArray("questions")
    FieldScenario(scenario.getString("id"), scenario.getString("title"),
        items.keys().asSequence().map { it to items.getString(it) }.toList(),
        List(questions.length()) { n ->
            val q = questions.getJSONObject(n)
            val guide = q.getString("guide")
            val steps = lessons.getValue(guide).getJSONArray("steps")
            val heading = q.getString("heading")
            val step = (0 until steps.length()).single { steps.getJSONObject(it).getString("heading") == heading }
            val options = q.getJSONArray("choices")
            Decision(q.getString("id"), q.getString("prompt"), List(options.length()) { options.getString(it) },
                q.getInt("answer"), guide, step, heading, steps.getJSONObject(step).getString("body"))
        })
}

@Composable
internal fun PracticeHub(manual: Manual, open: (String) -> Unit) {
    Page {
        manual.scenarios.forEach { scenario ->
            val checked = manual.prefs.getStringSet("kit/${scenario.id}", emptySet()).orEmpty()
            val count = scenario.tasks.count { it.first in checked }
            val answered = scenario.questions.count { manual.prefs.contains("answer/${scenario.id}/${it.id}") }
            Entry(scenario.title, "$count of ${scenario.tasks.size} checked · $answered of ${scenario.questions.size} practiced") {
                open("Checklist/${scenario.id}")
            }
        }
    }
}

@Composable
internal fun ScenarioChecklist(scenario: FieldScenario, prefs: SharedPreferences, open: (String) -> Unit) {
    val key = "kit/${scenario.id}"
    var checked by remember(key) { mutableStateOf(prefs.getStringSet(key, emptySet()).orEmpty().toSet()) }
    Page {
        Title(scenario.title)
        Section("Field checklist")
        val completed = scenario.tasks.count { it.first in checked }
        LinearProgressIndicator(progress = { completed.toFloat() / scenario.tasks.size.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth())
        Hint("$completed of ${scenario.tasks.size} checked")
        scenario.tasks.forEach { (id, label) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(
                value = id in checked, role = Role.Checkbox,
                onValueChange = { selected ->
                    checked = if (selected) checked + id else checked - id
                    prefs.edit().putStringSet(key, checked).apply()
                }
            ).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Checkbox(checked = id in checked, onCheckedChange = null)
                Text(label)
            }
        }
        Entry("Practice the decisions", "${scenario.questions.size} scenarios with field-guide explanations") {
            open("Drill/${scenario.id}")
        }
    }
}

@Composable
internal fun ScenarioDrill(scenario: FieldScenario, prefs: SharedPreferences, open: (String) -> Unit) {
    val questions = scenario.questions
    val positionKey = "drill/${scenario.id}"
    fun answerKey(q: Decision) = "answer/${scenario.id}/${q.id}"
    var index by remember(positionKey) {
        mutableIntStateOf(prefs.getInt(positionKey, 0).coerceIn(0, questions.size))
    }
    fun move(next: Int) {
        prefs.edit().putInt(positionKey, next).apply()
        index = next
    }
    key(index) {
        Page {
            Title(scenario.title)
            if (index == questions.size) {
                Section("Practice complete")
                val correct = questions.count { prefs.getString(answerKey(it), null) == it.choices[it.correct] }
                Hint("$correct of ${questions.size} correct. Tap a decision to review the explanation.")
                questions.forEachIndexed { n, q ->
                    val right = prefs.getString(answerKey(q), null) == q.choices[q.correct]
                    Entry(q.prompt, if (right) "Correct" else "Review answer") { move(n) }
                }
                OutlinedButton(onClick = {
                    val edit = prefs.edit()
                    questions.forEach { edit.remove(answerKey(it)) }
                    edit.putInt(positionKey, 0).apply()
                    index = 0
                }) { Text("Practice again") }
            } else {
                val q = questions[index]
                val answerKey = answerKey(q)
                var choice by remember { mutableStateOf(prefs.getString(answerKey, null)) }
                Hint("Decision ${index + 1} of ${questions.size}")
                Section(q.prompt)
                if (choice == null) {
                    q.choices.forEach { option -> Entry(option) {
                        choice = option
                        prefs.edit().putString(answerKey, option).apply()
                    } }
                } else {
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        if (choice == q.choices[q.correct]) Text("Correct · $choice")
                        else {
                            Hint("Your answer · $choice")
                            Text("Correct answer · ${q.choices[q.correct]}")
                        }
                    }
                    Text(q.explanation)
                    Entry("Open guide step", q.heading) { open("Guide/${q.guide}/${q.step}") }
                    Button(onClick = { move(index + 1) }) {
                        Text(if (index == questions.lastIndex) "See results" else "Next decision")
                    }
                }
            }
        }
    }
}
