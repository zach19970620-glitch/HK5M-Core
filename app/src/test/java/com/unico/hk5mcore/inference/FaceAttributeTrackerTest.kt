package com.unico.hk5mcore.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FaceAttributeTrackerTest {

    @Test
    fun activeTrackRetriesAtMostOncePerTwoSeconds() {
        val tracker = FaceAttributeTracker()
        tracker.updateDetections(face(score = 0.7f), nowMs = 100L)

        assertTrue(tracker.shouldRunAttributes(100L))
        tracker.markAttributeAttempt(100L)
        tracker.updateDetections(face(score = 0.7f), nowMs = 1_500L)
        tracker.updateDetections(face(score = 0.7f), nowMs = 2_099L)
        assertFalse(tracker.shouldRunAttributes(2_099L))
        tracker.updateDetections(face(score = 0.7f), nowMs = 2_100L)
        assertTrue(tracker.shouldRunAttributes(2_100L))
    }

    @Test
    fun highConfidenceTrackStillRefreshesAttributesAndFollowsMovingBox() {
        val tracker = FaceAttributeTracker()
        tracker.updateDetections(face(score = 0.9f), nowMs = 100L)
        tracker.markAttributeAttempt(100L)
        tracker.ingestAttributes(
            face(score = 0.9f, age = 30, gender = FaceResult.GENDER_MAN),
            nowMs = 100L,
        )

        tracker.updateDetections(
            face(left = 12, top = 12, right = 112, bottom = 112, score = 0.92f),
            nowMs = 300L,
        )
        val merged = tracker.mergedFaces(300L).single()

        assertEquals(12, merged.left)
        assertEquals(30, merged.age)
        assertEquals(FaceResult.GENDER_MAN, merged.gender)

        tracker.updateDetections(
            face(left = 13, top = 13, right = 113, bottom = 113, score = 0.94f),
            nowMs = 1_500L,
        )
        tracker.updateDetections(
            face(left = 14, top = 14, right = 114, bottom = 114, score = 0.95f),
            nowMs = 2_100L,
        )
        assertTrue(tracker.shouldRunAttributes(2_100L))
        tracker.markAttributeAttempt(2_100L)
        tracker.ingestAttributes(
            face(
                left = 14,
                top = 14,
                right = 114,
                bottom = 114,
                score = 0.95f,
                age = 30,
                gender = FaceResult.GENDER_MAN,
                emotion = FaceResult.EMOTION_HAPPY,
            ),
            nowMs = 2_100L,
        )
        assertEquals(
            FaceResult.EMOTION_HAPPY,
            tracker.mergedFaces(2_100L).single().emotion,
        )
    }

    @Test
    fun oldBoxIsBrieflyHeldOnlyWhenWholeFrameMisses() {
        val tracker = FaceAttributeTracker()
        tracker.updateDetections(face(score = 0.9f), nowMs = 100L)
        tracker.ingestAttributes(
            face(score = 0.9f, age = 30, gender = FaceResult.GENDER_MAN),
            nowMs = 100L,
        )

        tracker.updateDetections(emptyList(), nowMs = 200L)
        assertEquals(1, tracker.mergedFaces(200L).size)
        assertTrue(tracker.mergedFaces(501L).isEmpty())

        tracker.updateDetections(face(left = 12, top = 12, right = 112, bottom = 112), nowMs = 600L)
        assertEquals(30, tracker.mergedFaces(600L).single().age)
    }

    @Test
    fun twoFacesInSameFrameBindDistinctTracks() {
        val tracker = FaceAttributeTracker()
        val left = FaceResult(
            id = 0, left = 10, top = 10, right = 60, bottom = 60, score = 0.9f,
        )
        val right = FaceResult(
            id = 1, left = 200, top = 10, right = 250, bottom = 60, score = 0.85f,
        )

        tracker.updateDetections(listOf(left, right), nowMs = 100L)
        val merged = tracker.mergedFaces(100L)
        assertEquals(2, merged.size)

        // 下一帧两框都微移：必须仍是两条 track，不能因 Set.remove 失败而并到同一人
        tracker.updateDetections(
            listOf(
                left.copy(left = 12, right = 62),
                right.copy(left = 202, right = 252),
            ),
            nowMs = 200L,
        )
        val next = tracker.mergedFaces(200L)
        assertEquals(2, next.size)
        assertEquals(12, next.minOf { it.left })
        assertEquals(202, next.maxOf { it.left })
    }

    @Test
    fun iouUsesInclusiveDetectionCoordinates() {
        val first = face(left = 0, top = 0, right = 9, bottom = 9)
        val second = face(left = 5, top = 0, right = 14, bottom = 9)

        assertEquals(
            1f / 3f,
            FaceAttributeTracker.iou(first.single(), second.single()),
            absoluteTolerance = 0.0001f,
        )
    }

    private fun face(
        left: Int = 10,
        top: Int = 10,
        right: Int = 110,
        bottom: Int = 110,
        score: Float = 0.8f,
        age: Int = -1,
        gender: Int = -1,
        emotion: Int = -1,
    ): List<FaceResult> = listOf(
        FaceResult(
            id = 0,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            score = score,
            age = age,
            gender = gender,
            emotion = emotion,
        )
    )
}
