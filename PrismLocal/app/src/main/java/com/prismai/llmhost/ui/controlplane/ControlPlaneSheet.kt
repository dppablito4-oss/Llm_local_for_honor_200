package com.prismai.llmhost.ui.controlplane
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prismai.llmhost.BenchmarkRun
import com.prismai.llmhost.BenchmarkPresets
import com.prismai.llmhost.BenchmarkStatus
import com.prismai.llmhost.BuildConfig
import com.prismai.llmhost.DeviceCapabilityProfile
import com.prismai.llmhost.GenerationPerformance
import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.HuggingFaceModelEntry
import com.prismai.llmhost.ImportState
import com.prismai.llmhost.ModelDownloadState
import com.prismai.llmhost.ModelFitRating
import com.prismai.llmhost.ModelLoadDiagnostics
import com.prismai.llmhost.ModelReadiness
import com.prismai.llmhost.RuntimeStatus
import com.prismai.llmhost.ui.components.*
import com.prismai.llmhost.ui.theme.*
import com.prismai.llmhost.ui.benchmark.*
import com.prismai.llmhost.ui.*
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ControlPlaneSheet(
    models: List<String>,
    currentModel: String?,
    activeModelInfo: ModelStorageManager.ActiveModelInfo?,
    runtimeStatus: RuntimeStatus,
    importState: ImportState,
    modelDownloadState: ModelDownloadState,
    importStatus: String,
    recoveryTranscript: String?,
    generationSettings: GenerationSettings,
    generationPerformance: GenerationPerformance?,
    benchmarkRuns: List<BenchmarkRun>,
    benchmarkStatus: BenchmarkStatus,
    modelLoadDiagnostics: ModelLoadDiagnostics?,
    deviceCapabilityProfile: DeviceCapabilityProfile?,
    modelReadiness: List<ModelReadiness>,
    hfCatalog: List<HuggingFaceModelEntry>,
    isGenerating: Boolean,
    serviceAvailable: Boolean,
    onSwitchModel: (String) -> Unit,
    onDeleteModel: ((String) -> Unit)? = null,
    onImportModel: () -> Unit,
    onLinkModel: (() -> Unit)? = null,
    onCancelImport: () -> Unit,
    onDownloadModel: (String) -> Unit,
    onDownloadCustomHfModel: ((String, String) -> Unit)? = null,
    storageBreakdown: ModelStorageManager.StorageBreakdown? = null,
    onClearCache: (() -> Unit)? = null,
    onSettingsChange: (GenerationSettings) -> Unit,
    onRunBenchmark: (String) -> Unit,
    onRunThreadSweep: () -> Unit,
    onRunNativeBenchmark: () -> Unit,
    onExportBenchmarksCsv: () -> Unit,
    onExportBenchmarksJson: () -> Unit,
    onClearBenchmarks: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var pendingModelId by remember { mutableStateOf<String?>(null) }
    var riskyModel by remember { mutableStateOf<ModelReadiness?>(null) }
    var modelToDelete by remember { mutableStateOf<String?>(null) }
    val isImporting = importState is ImportState.Running
    val isLoadingModel = runtimeStatus == RuntimeStatus.LOADING_MODEL
    val controlsEnabled = !isGenerating && !isLoadingModel && !isImporting
    val requestModelSwitch: (String, ModelReadiness?) -> Unit = { modelId, readiness ->
        if (readiness?.fit?.rating != null && readiness.fit.rating != ModelFitRating.SAFE) {
            riskyModel = readiness
        } else {
            pendingModelId = modelId
            onSwitchModel(modelId)
        }
    }

    LaunchedEffect(isLoadingModel, currentModel) {
        if (!isLoadingModel) {
            pendingModelId = null
        }
    }

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Modelos", "Motor", "Almacenamiento", "Pruebas")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TabRow(
            selectedTabIndex = selectedTabIndex,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    modifier = Modifier.semantics { role = Role.Tab },
                    text = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (selectedTabIndex) {
                0 -> {
                    DashboardCard {
                        SectionHeader(
                            title = "Tus modelos locales",
                            subtitle = "Selecciona, importa o descarga modelos GGUF verificados",
                            action = {
                                Button(
                                    enabled = serviceAvailable && !isImporting && !isLoadingModel,
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                    onClick = onImportModel,
                                ) {
                                    Text("Importar GGUF", maxLines = 1, softWrap = false)
                                }
                            },
                        )

                        ExposedDropdownMenuBox(
                            expanded = menuExpanded,
                            onExpandedChange = { menuExpanded = !menuExpanded && serviceAvailable && !isLoadingModel && !isImporting },
                        ) {
                            OutlinedTextField(
                                modifier = Modifier
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth(),
                                readOnly = true,
                                value = compactModelName(pendingModelId ?: currentModel),
                                onValueChange = {},
                                label = { Text("Modelo seleccionado") },
                                placeholder = { Text(if (models.isEmpty()) "No hay modelos instalados" else "Seleccionar modelo") },
                                enabled = serviceAvailable && !isLoadingModel && !isImporting,
                                singleLine = true,
                                trailingIcon = {
                                    if (isLoadingModel) {
                                        InfinityLoadingIndicator(modifier = Modifier.size(28.dp))
                                    } else {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded)
                                    }
                                },
                            )
                            ExposedDropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                if (models.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No hay modelos instalados") },
                                        onClick = { menuExpanded = false },
                                        enabled = false,
                                    )
                                } else {
                                    models.forEach { modelId ->
                                        val readiness = modelReadiness.firstOrNull { it.info.id == modelId }
                                        DropdownMenuItem(
                                            text = {
                                                ModelPickerRow(
                                                    modelId = modelId,
                                                    readiness = readiness,
                                                )
                                            },
                                            enabled = !isLoadingModel,
                                            onClick = {
                                                menuExpanded = false
                                                requestModelSwitch(modelId, readiness)
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        if (isLoadingModel) {
                            LoadingModelStatus(
                                modelId = pendingModelId ?: currentModel,
                                diagnostics = modelLoadDiagnostics,
                            )
                        }

                        if (isImporting) {
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onCancelImport,
                            ) {
                                Text("Cancelar importación", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    enabled = controlsEnabled && serviceAvailable,
                                    onClick = onImportModel,
                                ) {
                                    Text("Importar GGUF", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (onLinkModel != null) {
                                    OutlinedButton(
                                        modifier = Modifier.weight(1f),
                                        enabled = controlsEnabled && serviceAvailable,
                                        onClick = onLinkModel,
                                    ) {
                                        Text("Vincular sin copiar", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }

                        if (importStatus.isNotEmpty()) {
                            Text(
                                text = importStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (importState is ImportState.Running) {
                            ImportProgressBar(importState)
                        }

                        if (models.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            Text(
                                text = "Instalados en este teléfono (${models.size})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            models.forEach { modelId ->
                                val readiness = modelReadiness.firstOrNull { it.info.id == modelId }
                                val isCurrent = modelId == currentModel
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp),
                                    color = if (isCurrent) PrismBlue.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isCurrent) PrismBlue.copy(alpha = 0.52f) else MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = compactModelName(modelId),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            readiness?.let { r ->
                                                Text(
                                                    text = "${formatBytes(r.info.bytes)} • ${r.fit.quantization ?: "cuantización desconocida"}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        if (isCurrent) {
                                            InfoBadge(text = "Activo", color = PrismGreen)
                                        } else {
                                            TextButton(
                                                enabled = serviceAvailable && controlsEnabled,
                                                onClick = { requestModelSwitch(modelId, readiness) },
                                            ) {
                                                Text("Usar", color = PrismBlue, style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                        TextButton(
                                            enabled = serviceAvailable && !isLoadingModel && !isGenerating && !isImporting,
                                            onClick = { modelToDelete = modelId },
                                        ) {
                                            Text("Eliminar", color = PrismRed, style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HuggingFaceDownloadPanel(
                        entries = hfCatalog,
                        state = modelDownloadState,
                        deviceCapabilityProfile = deviceCapabilityProfile,
                        enabled = serviceAvailable && !isImporting && !isLoadingModel && !isGenerating,
                        onDownload = onDownloadModel,
                        onDownloadCustom = onDownloadCustomHfModel,
                        onCancel = onCancelImport,
                    )

                    activeModelInfo?.let { model ->
                        ModelMetadata(
                            model = model,
                            diagnostics = modelLoadDiagnostics?.takeIf { it.modelId == model.id },
                            readiness = modelReadiness.firstOrNull { it.info.id == model.id },
                        )
                    }
                }
                1 -> {
                    RuntimeControls(
                        settings = generationSettings,
                        performance = generationPerformance,
                        enabled = controlsEnabled,
                        deviceCapabilityProfile = deviceCapabilityProfile,
                        currentModel = currentModel,
                        onSettingsChange = onSettingsChange,
                    )
                }
                2 -> {
                    storageBreakdown?.let { breakdown ->
                        StorageAnalyticsCard(
                            breakdown = breakdown,
                            onClearCache = onClearCache,
                        )
                    }

                    deviceCapabilityProfile?.let { profile ->
                        DeviceCapabilityCard(profile)
                    }

                    recoveryTranscript?.let { path ->
                        Text(
                            text = "Conversación recuperada: $path",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                3 -> {
                    BenchmarkCenter(
                        models = models,
                        activeModelInfo = activeModelInfo,
                        deviceCapabilityProfile = deviceCapabilityProfile,
                        runs = benchmarkRuns,
                        readiness = modelReadiness,
                        presets = BenchmarkPresets.defaults,
                        status = benchmarkStatus,
                        isGenerating = isGenerating,
                        disabledReason = benchmarkDisabledReason(
                            serviceAvailable = serviceAvailable,
                            performanceBuild = BuildConfig.LLMHOST_PERFORMANCE_BUILD,
                            currentModel = currentModel,
                            isGenerating = isGenerating,
                            status = benchmarkStatus,
                        ),
                        onRunPreset = onRunBenchmark,
                        onRunThreadSweep = onRunThreadSweep,
                        onRunNativeBenchmark = onRunNativeBenchmark,
                        onExportCsv = onExportBenchmarksCsv,
                        onExportJson = onExportBenchmarksJson,
                        onClear = onClearBenchmarks,
                    )
                }
            }
        }
    }

    riskyModel?.let { readiness ->
        val isTooLarge = readiness.fit.rating == ModelFitRating.TOO_LARGE
        val isProvenUsable = readiness.fit.reason.startsWith("Proven usable")
        AlertDialog(
            onDismissRequest = { riskyModel = null },
            title = {
                Text(
                    text = when {
                        isProvenUsable -> "Cargar modelo verificado en este dispositivo"
                        isTooLarge -> "Modelo demasiado grande (requiere mucha RAM)"
                        else -> "¿Cargar modelo con riesgo?"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = compactModelName(readiness.info.id),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${readiness.fit.reason}. RAM estimada: ${formatBytes(readiness.fit.requiredRamBytes)}; disponible tras descargar el modelo actual: ${formatBytes(readiness.fit.availableRamAfterUnloadBytes)}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (isProvenUsable) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Uso verificado: ejecuciones anteriores confirman que este modelo funciona en tu dispositivo sin fallos de memoria.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    } else if (isTooLarge) {
                        Text(
                            text = "Se estima que este modelo necesita más RAM de la disponible o supera el límite de esta compilación. " +
                                "Cierra otras aplicaciones o reduce el contexto e inténtalo otra vez.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        text = "Velocidad esperada: ${predictionRange(readiness.prediction)} (${readiness.prediction.basis}).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isLoadingModel && !isImporting,
                    onClick = {
                        pendingModelId = readiness.info.id
                        onSwitchModel(readiness.info.id)
                        riskyModel = null
                    },
                ) {
                    Text(
                        text = if (isTooLarge) "Cargar de todos modos" else "Cargar modelo",
                        color = if (isProvenUsable) MaterialTheme.colorScheme.primary else if (isTooLarge) PrismRed else MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { riskyModel = null }) {
                    Text("Cancelar")
                }
            },
        )
    }

    modelToDelete?.let { targetId ->
        val targetReadiness = modelReadiness.firstOrNull { it.info.id == targetId }
        val sizeLabel = targetReadiness?.let { formatBytes(it.info.bytes) } ?: "modelo"
        AlertDialog(
            onDismissRequest = { modelToDelete = null },
            title = { Text("¿Eliminar modelo?") },
            text = {
                Text("¿Quieres eliminar ${compactModelName(targetId)} ($sizeLabel) del teléfono? Esta acción no se puede deshacer.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = targetId
                        modelToDelete = null
                        onDeleteModel?.invoke(toDelete)
                    },
                ) {
                    Text("Eliminar", color = PrismRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { modelToDelete = null }) {
                    Text("Cancelar")
                }
            },
        )
    }
}

@Composable
private fun LoadingModelStatus(modelId: String?, diagnostics: ModelLoadDiagnostics?) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InfinityLoadingIndicator(modifier = Modifier.size(34.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Cargando modelo",
                    style = MaterialTheme.typography.labelMedium,
                    color = PrismBlue,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = compactModelName(modelId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                diagnostics?.let { info ->
                    Text(
                        text = "RAM ${info.availableMemoryMb ?: 0} MB | ${info.state}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelPickerRow(
    modelId: String,
    readiness: ModelReadiness?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = modelId,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        readiness?.let { info ->
            Text(
                text = "${info.performance.label} • ${info.fit.quantization ?: "cuantización desconocida"} • esperado ${predictionRange(info.prediction)}",
                style = MaterialTheme.typography.labelSmall,
                color = performanceColor(info.performance.tier),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DeviceCapabilityCard(profile: DeviceCapabilityProfile) {
    DashboardCard {
        SectionHeader(title = "Dispositivo", subtitle = "Capacidad disponible para la inferencia local")
        if (profile.isSamsungS25Ultra) {
            val ramTierLabel = if (profile.s25RamTier == "16GB_REGION") "16GB LPDDR5X (Asia 1TB Tier)" else "12GB LPDDR5X (US/Global Tier)"
            InfoBadge(
                text = "Samsung S25 Ultra • Snapdragon 8 Elite • $ramTierLabel",
                color = PrismBlue,
            )
        }
        if (profile.hasSPenSupport) {
            InfoBadge(
                text = "Controles flotantes del S-Pen activos",
                color = PrismGreen,
            )
        }
        MetricGrid(
            listOf(
                "RAM libre" to formatBytes(profile.availableRamBytes),
                "RAM total" to formatBytes(profile.totalRamBytes),
                "Memoria de la app" to "${profile.memoryClassMb} MB",
                "CPU" to "${profile.cpuCoreCount} núcleos",
                "OS" to "Android ${profile.androidSdk}",
                "ABI" to (profile.abis.firstOrNull() ?: "Desconocida"),
                "Almacenamiento" to "${formatBytes(profile.storageFreeBytes)} libres",
                "Batería" to (profile.batteryPercent?.let { "$it%" } ?: "Desconocida"),
                "Temperatura" to (profile.thermalStatus?.replaceFirstChar { it.titlecase(Locale.US) } ?: "Desconocida"),
            )
        )
        if (profile.lowMemory) {
            InfoBadge(text = "Android informa que queda poca memoria", color = PrismAmber)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HuggingFaceDownloadPanel(
    entries: List<HuggingFaceModelEntry>,
    state: ModelDownloadState,
    deviceCapabilityProfile: DeviceCapabilityProfile?,
    enabled: Boolean,
    onDownload: (String) -> Unit,
    onDownloadCustom: ((String, String) -> Unit)? = null,
    onCancel: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var selectedEntryId by remember(entries) { mutableStateOf(entries.firstOrNull()?.id) }
    val selectedEntry = entries.firstOrNull { it.id == selectedEntryId } ?: entries.firstOrNull()
    DashboardCard {
        SectionHeader(
            title = "Descargar modelos verificados",
            subtitle = "Modelos GGUF de Hugging Face. Las descargas se reanudan y se verifica SHA-256 antes de instalarlas.",
        )
        when (state) {
            ModelDownloadState.Idle -> Unit
            ModelDownloadState.Cancelled -> Text(
                text = "Descarga cancelada",
                style = MaterialTheme.typography.bodySmall,
                color = PrismAmber,
            )
            is ModelDownloadState.Failure -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "${state.entryName}: ${state.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = PrismRed,
                    )
                    selectedEntry?.let { entry ->
                        TextButton(
                            contentPadding = PaddingValues(0.dp),
                            onClick = { onDownload(entry.id) },
                        ) {
                            Text("Reintentar descarga", color = PrismBlue, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            is ModelDownloadState.Success -> Text(
                text = if (state.integrityVerified) {
                    "${state.entryName} descargado"
                } else {
                    "${state.entryName} descargado (sin verificar: no hay SHA-256)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.integrityVerified) PrismGreen else PrismAmber,
            )
            is ModelDownloadState.Running -> {
                Text(
                    text = "${state.stage.label()} ${state.entry.name}: ${formatBytes(state.bytesDone)} / ${state.totalBytes?.let(::formatBytes) ?: "desconocido"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.message?.takeIf { it.isNotBlank() }?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DownloadProgressBar(
                    bytesDone = state.bytesDone,
                    totalBytes = state.totalBytes,
                )
                Button(onClick = onCancel) {
                    Text("Cancelar descarga")
                }
            }
        }
        if (entries.isNotEmpty()) {
            ExposedDropdownMenuBox(
                expanded = menuExpanded,
                onExpandedChange = {
                    if (enabled && state !is ModelDownloadState.Running) {
                        menuExpanded = !menuExpanded
                    }
                },
            ) {
                OutlinedTextField(
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                    value = selectedEntry?.name.orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    enabled = enabled && state !is ModelDownloadState.Running,
                    label = { Text("Elegir del catálogo") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded)
                    },
                )
                ExposedDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    entries.forEach { entry ->
                        DropdownMenuItem(
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(entry.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = "${entry.parameters} | ${entry.quantization} | ${formatBytes(entry.expectedBytes)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                selectedEntryId = entry.id
                                menuExpanded = false
                            },
                        )
                    }
                }
            }
        }
        selectedEntry?.let { entry ->
            DownloadCatalogRow(
                entry = entry,
                deviceCapabilityProfile = deviceCapabilityProfile,
                enabled = enabled && state !is ModelDownloadState.Running,
                onDownload = { onDownload(entry.id) },
            )
        }
    }
}

@Composable
private fun DownloadCatalogRow(
    entry: HuggingFaceModelEntry,
    deviceCapabilityProfile: DeviceCapabilityProfile?,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = PrismGlass.copy(alpha = 0.36f),
        contentColor = PrismText,
        border = BorderStroke(1.dp, PrismGlassBorder.copy(alpha = 0.44f)),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InfoBadge(text = entry.parameters, color = PrismBlue)
                        InfoBadge(text = entry.quantization, color = PrismViolet)
                        InfoBadge(text = formatBytes(entry.expectedBytes), color = PrismSlate)
                    }
                }
                Button(
                    enabled = enabled && hasEnoughFreeStorage(entry, deviceCapabilityProfile),
                    onClick = onDownload,
                ) {
                    Text("Descargar")
                }
            }
            Text(
                text = entry.notes,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            MetricGrid(listOf("Licencia" to entry.license, "Repositorio" to entry.repoId))
            if (!hasEnoughFreeStorage(entry, deviceCapabilityProfile)) {
                Text(
                    text = "Se necesita más espacio para descargar e instalar el modelo",
                    style = MaterialTheme.typography.labelSmall,
                    color = PrismRed,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
internal fun DownloadProgressBar(bytesDone: Long, totalBytes: Long?) {
    val progress = totalBytes
        ?.takeIf { it > 0L }
        ?.let { total -> (bytesDone.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat() }
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = PrismBlue,
            trackColor = AssistantBubble,
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = PrismBlue,
            trackColor = AssistantBubble,
        )
    }
}

internal fun ModelDownloadState.Running.Stage.label(): String = when (this) {
    ModelDownloadState.Running.Stage.QUEUED -> "En cola"
    ModelDownloadState.Running.Stage.VERIFYING_METADATA -> "Comprobando"
    ModelDownloadState.Running.Stage.DOWNLOADING -> "Descargando"
    ModelDownloadState.Running.Stage.VERIFYING_FILE -> "Verificando"
    ModelDownloadState.Running.Stage.IMPORTING -> "Importando"
}

private fun hasEnoughFreeStorage(
    entry: HuggingFaceModelEntry,
    profile: DeviceCapabilityProfile?,
): Boolean =
    profile?.let { it.storageFreeBytes > entry.expectedBytes * 2L + 512L * 1024L * 1024L } ?: true

@Composable
private fun ImportProgressBar(state: ImportState.Running) {
    val progress = state.progressFraction()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = PrismBlue,
                trackColor = AssistantBubble,
            )
            Text(
                text = "${(progress * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = PrismBlue,
                trackColor = AssistantBubble,
            )
        }
    }
}

private fun ImportState.Running.progressFraction(): Float? =
    totalBytes
        ?.takeIf { it > 0L }
        ?.let { total -> (bytesCopied.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat() }

@Composable
private fun ModelMetadata(
    model: ModelStorageManager.ActiveModelInfo,
    diagnostics: ModelLoadDiagnostics?,
    readiness: ModelReadiness?,
) {
    val metadata = model.validation.metadata
    DashboardCard {
        SectionHeader(
            title = "Modelo cargado",
            subtitle = polishedModelName(model.id),
        )
        MetricGrid(
            listOfNotNull(
                "Tamaño" to formatBytes(model.bytes),
                "Formato" to "GGUF v${model.validation.ggufVersion}",
                "Validación" to model.validation.status.replaceFirstChar { it.titlecase(Locale.US) },
                "SHA-256" to shortHash(model.sha256),
                metadata?.architecture?.let { "Familia" to it.uppercase(Locale.US) },
                metadata?.sizeLabel?.let { "Parámetros" to it },
                metadata?.contextLength?.let { "Contexto" to it.toString() },
                metadata?.fileType?.let { "Tipo" to it.toString() },
                "Plantilla" to if (metadata?.hasChatTemplate == true) "Chat" else "Desconocida",
                diagnostics?.loadMs?.let { "Tiempo de carga" to "$it ms" },
                diagnostics?.availableMemoryMb?.let { "RAM libre" to "$it MB" },
                diagnostics?.state?.let { "Estado" to it.replaceFirstChar { char -> char.titlecase(Locale.US) } },
                diagnostics?.backendName?.let { "Motor" to it },
                diagnostics?.gpuLayersOffloaded?.takeIf { it > 0 }?.let { "Capas en GPU" to "$it" },
            )
        )
        readiness?.let { modelReadiness ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = performanceColor(modelReadiness.performance.tier).copy(alpha = 0.10f),
                contentColor = performanceColor(modelReadiness.performance.tier),
                border = BorderStroke(1.dp, performanceColor(modelReadiness.performance.tier).copy(alpha = 0.22f)),
                shadowElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = modelReadiness.performance.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Necesita ${formatBytes(modelReadiness.fit.requiredRamBytes)} • esperado ${predictionRange(modelReadiness.prediction)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageAnalyticsCard(
    breakdown: ModelStorageManager.StorageBreakdown,
    onClearCache: (() -> Unit)?,
) {
    DashboardCard {
        SectionHeader(
            title = "Almacenamiento y caché",
            subtitle = "Uso del espacio en el dispositivo",
        )
        MetricGrid(
            listOf(
                "Modelos GGUF" to formatBytes(breakdown.installedModelsBytes),
                "Caché de descargas" to formatBytes(breakdown.downloadsCacheBytes),
                "Espacio libre" to formatBytes(breakdown.freeStorageBytes),
                "Espacio total" to formatBytes(breakdown.totalStorageBytes),
            )
        )
        if (onClearCache != null) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onClearCache,
            ) {
                Text("Limpiar caché y archivos temporales")
            }
        }
    }
}
