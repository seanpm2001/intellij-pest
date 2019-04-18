package rs.pest.livePreview

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.xml.util.XmlStringUtil
import org.intellij.lang.annotations.Language
import rs.pest.PestBundle
import java.awt.Color
import javax.swing.JPanel

fun rgbToAttributes(rgb: String) = stringToColor(rgb)
	?.let { TextAttributes().apply { foregroundColor = it } }

private fun stringToColor(rgb: String) = when {
	rgb.startsWith("#") -> rgb.drop(1).toIntOrNull(16)?.let(::Color)
	else -> Color::class.java.fields
		.firstOrNull { it.name.equals(rgb, ignoreCase = true) }
		?.let { it.get(null) as? Color }
}

fun textAttrFromDoc(docComment: PsiComment) =
	docComment.text.removePrefix("///").trim().let(::rgbToAttributes)

@Language("RegExp")
private val lexicalRegex = Regex("\\A(\\d+)\\^(\\d+)\\^(.*)$")

@Language("RegExp")
private val errMsgRegex = Regex("\\A\\s+-->\\s+(\\d+):(\\d+)\\p{all}*")

class LivePreviewAnnotator : Annotator {
	override fun annotate(element: PsiElement, holder: AnnotationHolder) {
		if (element !is LivePreviewFile) return
		val pestFile = element.pestFile ?: return
		val ruleName = element.ruleName ?: return
		val rules = pestFile.rules().map { it.name to it }.toMap()
		if (rules.isEmpty()) return
		if (pestFile.errors.any()) return
		if (pestFile.availableRules.none()) return
		val vm = pestFile.vm
		when (val res = try {
			vm.renderCode(ruleName, element.text)
		} catch (e: Exception) {
			vm.reboot()
			vm.loadVM(pestFile.text)
			vm.renderCode(ruleName, element.text)
		}) {
			is Rendering.Err -> errMsgRegex.matchEntire(res.msg)?.apply {
				val dom = PsiDocumentManager.getInstance(element.project).getDocument(element) ?: return@apply
				val length = dom.textLength
				if (length == 0) return@apply
				val (_, lineS, colS) = groupValues
				val line = lineS.toIntOrNull() ?: return@apply
				val col = colS.toIntOrNull() ?: return@apply
				val lineStart = dom.getLineStartOffset(line - 1)
				val offset = lineStart + col - 1
				val range = if (offset >= dom.textLength) TextRange(length - 1, length)
				else TextRange(offset, offset + 1)
				holder.createErrorAnnotation(range, null).apply {
					//language=HTML
					tooltip = XmlStringUtil.wrapInHtml(res.msg).replace("\n", "<br/>")
				}
			} ?: ApplicationManager.getApplication().invokeLater {
				val project = element.project
				val dom = PsiDocumentManager.getInstance(project).getDocument(element) ?: return@invokeLater
				val panel = JPanel().apply { add(JBTextArea().apply { text = res.msg }) }
				val editor = EditorFactory.getInstance()
					.getEditors(dom, project)
					.firstOrNull()
					?: return@invokeLater
				val factory = JBPopupFactory.getInstance()
				factory
					.createBalloonBuilder(panel)
					.setTitle(PestBundle.message("pest.annotator.live-preview.error.title"))
					.setFillColor(JBColor.RED)
					.createBalloon()
					.show(factory.guessBestPopupLocation(editor), Balloon.Position.below)
			}
			is Rendering.Ok -> res.lexical.mapNotNull(lexicalRegex::matchEntire).forEach {
				val (_, start, end, rule) = it.groupValues
				val psiRule = rules[rule] ?: return@forEach
				val range = TextRange(start.toInt(), end.toInt())
				val annotation = holder.createInfoAnnotation(range, rule)
				psiRule.docComment?.let(::textAttrFromDoc)?.let { attr ->
					annotation.enforcedTextAttributes = attr
				}
			}
		}
	}
}
