package com.moyeota.driver.presentation.core

// 리포지토리가 만든 실패 진단을 화면까지 그대로 나르는 공용 게이트.
//
// 배경(QA D-1·D-4): 리포지토리는 404 의 원인을 좁히려고 /api/v1/config 까지 한 번 더 읽어
// "서버에서 택시 서비스가 꺼져 있어요 · 운영자에게 문의해 주세요" 같은 안내를 만든다.
// 그런데 ViewModel 의 `catch (e: Exception)` 이 고정 문구로 덮어 버려 그 진단이 전부 버려졌고,
// 기사는 멀쩡한 비밀번호를 계속 다시 입력했다. 그래서 e.message 를 우선 노출한다.
//
// 다만 **서버 원문을 무조건 그대로 뿌리지는 않는다.** 역직렬화가 어긋나면 JSON/HTML 본문이,
// 플랫폼 예외면 호스트·IP·스택 조각이 message 에 그대로 담겨 온다 — 화면에 내부 정보가 새거나
// 레이아웃이 깨진다. "사람이 읽을 만한 한국어 한 문장"만 통과시키고 그 밖은 폴백으로 돌린다.

/** 화면에 그대로 띄울 수 있다고 보는 메시지의 최대 길이 — 넘으면 원문 덤프·연쇄 메시지로 본다 */
private const val MAX_USER_MESSAGE_LENGTH = 120

/**
 * 예외가 들고 온 사용자 노출용 메시지, 쓸 수 없으면 [fallback].
 *
 * 통과 조건은 모두 "리포지토리가 의도적으로 만든 한국어 안내"의 특징이다:
 * 비어 있지 않고 · 120자 이내 · 한 줄 · JSON/HTML 본문으로 시작하지 않고 · 한글을 포함한다.
 */
internal fun Throwable?.userMessageOr(fallback: String): String =
    this?.message?.trim()?.takeIf { it.isDisplayableUserMessage() } ?: fallback

private fun String.isDisplayableUserMessage(): Boolean =
    isNotEmpty() &&
        length <= MAX_USER_MESSAGE_LENGTH &&
        none { it == '\n' || it == '\r' } &&
        first() !in "{[<" &&
        // 한글이 없으면 플랫폼 영문 예외("Unable to resolve host …")나 원문 덤프다 — 기사에게 단서가 못 된다
        any { it in '가'..'힣' }
