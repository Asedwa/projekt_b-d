package org.kde.plasmawave.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

fun createBreezeShapes(smallRadius: Int, mediumRadius: Int, largeRadius: Int): Shapes {
    return Shapes(
        small = RoundedCornerShape(smallRadius.dp),
        medium = RoundedCornerShape(mediumRadius.dp),
        large = RoundedCornerShape(largeRadius.dp)
    )
}

val BreezeDefaultShapes = createBreezeShapes(6, 10, 18)
