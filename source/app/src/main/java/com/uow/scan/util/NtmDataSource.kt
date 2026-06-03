package com.uow.scan.util

/**
 * What the Network Traffic Monitor screens need from a data source. Two implementations:
 *  - [NtmDemoData] — the canned, deterministic dataset (used under the long-press demo override).
 *  - [NtmLiveRepository] — real on-device data from the unified tunnel ([NtmStore]) + NetworkStats
 *    ([DataUsageHelper]) + tracker classification ([TrackerDomainMatcher]).
 *
 * The screen-facing model types (NtmApp/Dest/AggStats/Posture/Finding) live in [NtmDemoData] so
 * both sources speak exactly the same shapes and the screens stay source-agnostic.
 */
interface NtmDataSource {
    fun apps(): List<NtmDemoData.NtmApp>
    fun agg(blocking: Boolean): NtmDemoData.AggStats
    fun posture(blocking: Boolean): NtmDemoData.Posture
    fun findings(blocking: Boolean): List<NtmDemoData.Finding>
}
