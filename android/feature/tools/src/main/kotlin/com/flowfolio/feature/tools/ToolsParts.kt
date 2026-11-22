package com.flowfolio.feature.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.flowfolio.core.designsystem.FlowFolio
import com.flowfolio.core.designsystem.FlowFolioGlyph
import com.flowfolio.core.designsystem.FlowFolioIconButton
import com.flowfolio.core.designsystem.FlowFolioType
import com.flowfolio.core.designsystem.Glyph

/** A screen's title block: a round back button, the title in Fraunces and a quiet line under it. */
@Composable
internal fun ToolsHeader(
    title: String,
    sub: String,
    backLabel: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val p = FlowFolio.palette
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            FlowFolioIconButton(
                onClick = onBack,
                glass = true,
                modifier = Modifier.padding(end = 14.dp),
                tooltip = backLabel
            ) {
                FlowFolioGlyph(Glyph.Back, backLabel, color = p.text)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = FlowFolioType.screenTitle.merge(FlowFolio.title),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                sub,
                style = FlowFolioType.sub.merge(FlowFolio.hint),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = FlowFolioType.eyebrow.copy(color = FlowFolio.palette.ink3),
        modifier = modifier.padding(top = 18.dp, bottom = 8.dp)
    )
}

/** A text field in the app's colours. */
@Composable
internal fun ToolField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    number: Boolean = false,
    secret: Boolean = false,
    singleLine: Boolean = true,
    supporting: String? = null
) {
    val p = FlowFolio.palette
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) {
                KeyboardType.Password
            } else if (number) {
                KeyboardType.Number
            } else {
                KeyboardType.Text
            }
        ),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        supportingText = supporting?.let { { Text(it) } },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = p.ink,
            unfocusedTextColor = p.ink,
            focusedBorderColor = p.accent,
            unfocusedBorderColor = p.line,
            focusedLabelColor = p.accent,
            unfocusedLabelColor = p.ink2,
            cursorColor = p.accent,
            focusedSupportingTextColor = p.ink3,
            unfocusedSupportingTextColor = p.ink3
        ),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

/** A labelled slider with its value on the right. */
@Composable
internal fun ToolSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    display: String,
    range: ClosedFloatingPointRange<Float> = 0f..1f
) {
    val p = FlowFolio.palette
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = FlowFolioType.rowLabel.copy(color = p.ink))
            Text(display, style = FlowFolioType.rowDetail.copy(color = p.ink2))
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = p.accent,
                activeTrackColor = p.accent,
                inactiveTrackColor = p.line
            )
        )
    }
}
