package ilab.iptv.player.core.player

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.FailureClass

/**
 * Turns an [AppError] into the sentence the player screen shows (P1-4 item 3: "失败要有可读提示与重试
 * 入口").
 *
 * WHY IT IS PURE: this is the part of the failure path a unit test can pin down without a device.
 * The rules are (docs/02 §4.6): name the *cause* in user words, and say whether trying again is
 * worth it — [FailureClass.CANCELLED] is not a failure at all and has no text.
 */
object PlaybackFailureText {

    fun of(error: AppError): String {
        val reason = when (error.failure) {
            FailureClass.HTTP_CLIENT -> "该频道源不可用（HTTP ${error.httpStatus ?: "4xx"}）"
            FailureClass.HTTP_SERVER -> "频道源服务器出错（HTTP ${error.httpStatus ?: "5xx"}）"
            FailureClass.TIMEOUT -> "连接超时"
            FailureClass.NET_UNREACHABLE -> "网络不可达"
            FailureClass.TLS -> "安全连接失败"
            FailureClass.PARSE -> "流格式无法解析"
            FailureClass.DECODE_UNSUPPORTED -> "设备不支持该流的编码"
            FailureClass.DECODE_CORRUPT -> "流数据损坏"
            FailureClass.EMPTY_MEDIA -> "流里没有媒体内容"
            FailureClass.PLAYLIST_GONE -> "该频道的所有源都已失效"
            FailureClass.STORAGE -> "本地存储不可用"
            FailureClass.PERMISSION -> "缺少权限"
            FailureClass.NO_CAPABILITY -> "没有可用的播放引擎"
            FailureClass.CANCELLED -> "播放已取消"
            FailureClass.UNKNOWN -> "播放失败"
        }
        val hint = when {
            error.failure == FailureClass.CANCELLED -> ""
            error.retryable -> "，按「重试」再试一次"
            else -> "，换一个源或频道"
        }
        return reason + hint
    }

    /**
     * Short label for the info bar's status line. Kept separate from [of] because the two have
     * different jobs: this one has to read well at a glance over live video.
     */
    fun shortLabel(error: AppError): String = when (error.failure) {
        FailureClass.CANCELLED -> "已取消"
        FailureClass.TIMEOUT -> "超时"
        FailureClass.NET_UNREACHABLE -> "网络不可达"
        FailureClass.DECODE_UNSUPPORTED, FailureClass.DECODE_CORRUPT -> "编码不支持"
        FailureClass.EMPTY_MEDIA -> "空流"
        FailureClass.PLAYLIST_GONE -> "全部源失效"
        FailureClass.NO_CAPABILITY -> "无可用引擎"
        else -> "播放失败"
    }
}
