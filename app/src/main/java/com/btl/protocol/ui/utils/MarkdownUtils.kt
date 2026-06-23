package com.btl.protocol.ui.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

fun String.parseMarkdown(): AnnotatedString {
    return buildAnnotatedString {
        var currentIndex = 0
        // Match bold, italic, strikethrough, and code
        val pattern = Regex("(\\*\\*(.*?)\\*\\*)|(\\*(.*?)\\*)|(~~(.*?)~~)|(`(.*?)`)")
        val matches = pattern.findAll(this@parseMarkdown)
        
        for (match in matches) {
            val range = match.range
            if (currentIndex < range.first) {
                append(this@parseMarkdown.substring(currentIndex, range.first))
            }
            
            val value = match.value
            when {
                value.startsWith("**") && value.endsWith("**") -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(value.removeSurrounding("**"))
                    pop()
                }
                value.startsWith("*") && value.endsWith("*") -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(value.removeSurrounding("*"))
                    pop()
                }
                value.startsWith("~~") && value.endsWith("~~") -> {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    append(value.removeSurrounding("~~"))
                    pop()
                }
                value.startsWith("`") && value.endsWith("`") -> {
                    pushStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0x33000000)
                    ))
                    append(value.removeSurrounding("`"))
                    pop()
                }
            }
            currentIndex = range.last + 1
        }
        
        if (currentIndex < this@parseMarkdown.length) {
            append(this@parseMarkdown.substring(currentIndex))
        }
    }
}
