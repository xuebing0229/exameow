use crate::ai::AIClient;
use crate::error::CoreError;
use serde::{Deserialize, Serialize};

use super::Question;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TextImportRefactorResult {
    pub cleaned_text: String,
    #[serde(default)]
    pub notes: Vec<String>,
}

pub fn build_text_import_refactor_system_prompt() -> &'static str {
    r#"You clean imported exam-bank text for a deterministic local parser. You do not solve questions or invent missing content.

Return ONLY JSON with this shape:
{"cleaned_text":"...","notes":["..."]}

Rules:
1. Preserve every question, its wording, options, supplied answer, analysis, section title, and order.
2. Put each question in a separate block using: number + stem, A-H options, Answer:, Analysis:.
3. Normalize obvious layout noise, broken option lines, full-width punctuation, and answer-section formatting.
4. Keep single choice, multiple choice, true/false, fill blank, and short answer distinct when the source provides enough evidence.
5. Never infer or solve an answer. If an answer cannot be matched reliably, write Answer: [NEEDS CONFIRMATION].
6. Do not remove a suspicious fragment merely because it is hard to parse. Keep it and mention it in notes.
7. cleaned_text must be plain text, not Markdown fences. Notes must be short and only mention uncertainties or material repairs."#
}

fn build_text_import_refactor_user_prompt(
    raw_text: &str,
    current_questions: &[Question],
    warnings: &[String],
) -> Result<String, CoreError> {
    let current = serde_json::to_string(current_questions)
        .map_err(|e| CoreError::Exam(format!("Text import JSON encode error: {e}")))?;
    let warning_text = if warnings.is_empty() {
        "(none)".to_string()
    } else {
        warnings.join("\n")
    };
    Ok(format!(
        "RAW TEXT:\n{raw_text}\n\nCURRENT LOCAL PARSE:\n{current}\n\nLOCAL WARNINGS:\n{warning_text}"
    ))
}

pub fn parse_text_import_refactor(raw: &str) -> Result<TextImportRefactorResult, CoreError> {
    let cleaned = raw
        .trim()
        .trim_start_matches("```json")
        .trim_start_matches("```")
        .trim_end_matches("```")
        .trim();
    let start = cleaned.find('{');
    let end = cleaned.rfind('}');
    let json = match (start, end) {
        (Some(s), Some(e)) if e >= s => &cleaned[s..=e],
        _ => cleaned,
    };
    let result: TextImportRefactorResult = serde_json::from_str(json)
        .map_err(|e| CoreError::Exam(format!("Text import refactor JSON parse error: {e}")))?;
    if result.cleaned_text.trim().is_empty() {
        return Err(CoreError::Exam("AI returned empty cleaned text".to_string()));
    }
    Ok(result)
}

pub async fn refactor_text_import(
    client: &AIClient,
    raw_text: &str,
    current_questions: &[Question],
    warnings: &[String],
    model: &str,
) -> Result<TextImportRefactorResult, CoreError> {
    let user_prompt = build_text_import_refactor_user_prompt(raw_text, current_questions, warnings)?;
    let response = client
        .chat(build_text_import_refactor_system_prompt(), &user_prompt, model)
        .await?;
    parse_text_import_refactor(&response)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_fenced_result() {
        let raw = "```json\n{\"cleaned_text\":\"1. Q\\nA. x\\nAnswer: A\",\"notes\":[]}\n```";
        let result = parse_text_import_refactor(raw).unwrap();
        assert!(result.cleaned_text.contains("Answer: A"));
    }

    #[test]
    fn rejects_empty_cleaned_text() {
        assert!(parse_text_import_refactor("{\"cleaned_text\":\"\",\"notes\":[]}").is_err());
    }
}
