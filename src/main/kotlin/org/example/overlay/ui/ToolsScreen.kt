package org.example.overlay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.overlay.app.AppState
import org.example.overlay.backend.ToolInfo

/**
 * Список инструментов из `GET /tools` и ручной вызов `POST /tools/{name}` — тот же путь,
 * которым позже пойдёт `function_call` от модели. Нужен, чтобы отделять проблемы бэкенда
 * от проблем голосового тракта.
 */
@Composable
fun ToolsScreen(state: AppState) {
    val tools by state.tools.collectAsState()
    val busy by state.busy.collectAsState()
    val output by state.lastToolOutput.collectAsState()
    val log by state.toolLog.collectAsState()

    var selected by remember { mutableStateOf<ToolInfo?>(null) }
    var arguments by remember { mutableStateOf("{}") }

    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.width(280.dp).fillMaxHeight()) {
            Text(
                "Инструменты (${tools.size})",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = OverlayColors.Text,
            )
            Spacer(Modifier.height(8.dp))
            if (tools.isEmpty()) {
                Text("Список пуст — войди в аккаунт.", color = OverlayColors.TextDim, fontSize = 12.sp)
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(tools) { tool ->
                    ToolRow(tool = tool, selected = tool.name == selected?.name) { selected = tool }
                }
            }
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.fillMaxSize()) {
            val current = selected
            if (current == null) {
                Text("Выбери инструмент слева.", color = OverlayColors.TextDim, fontSize = 13.sp)
                return@Column
            }

            Text(current.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OverlayColors.Text)
            current.description?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = OverlayColors.TextDim, fontSize = 12.sp)
            }
            current.parameters?.let { schema ->
                Spacer(Modifier.height(8.dp))
                Text("Схема аргументов", color = OverlayColors.TextDim, fontSize = 12.sp)
                Text(
                    text = schema.toString(),
                    color = OverlayColors.TextDim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().height(80.dp).verticalScroll(rememberScrollState()),
                )
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = arguments,
                onValueChange = { arguments = it },
                label = { Text("Аргументы (JSON)") },
                modifier = Modifier.fillMaxWidth().height(110.dp),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { state.callTool(current.name, arguments) }, enabled = !busy) {
                Text("Вызвать")
            }

            Spacer(Modifier.height(12.dp))
            Text("Ответ", color = OverlayColors.TextDim, fontSize = 12.sp)
            Text(
                text = output ?: "—",
                color = OverlayColors.Text,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            )

            Spacer(Modifier.height(12.dp))
            Text("Лог вызовов", color = OverlayColors.TextDim, fontSize = 12.sp)
            LazyColumn(modifier = Modifier.fillMaxWidth().height(90.dp)) {
                items(log.asReversed()) { entry ->
                    Text(
                        text = "${entry.name} → ${entry.status} (${entry.durationMs} мс)" +
                            (entry.message?.let { " · $it" } ?: ""),
                        color = if (entry.isFailure) OverlayColors.Error else OverlayColors.Text,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolRow(tool: ToolInfo, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(
                color = if (selected) OverlayColors.Surface else OverlayColors.Background,
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(tool.name, color = OverlayColors.Text, fontSize = 13.sp)
        tool.executionMode?.let { Text(it, color = OverlayColors.TextDim, fontSize = 11.sp) }
    }
}
