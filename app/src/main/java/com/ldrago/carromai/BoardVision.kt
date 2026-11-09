fun detectStackedPucks(rawPucks: List<Puck>, puckRadius: Float): List<Puck> {
    return rawPucks.map { p1 ->
        // Agar kisi bhi dusri puck ka center 1.75 * radius se paas hai, matlab wo ek-dusre par chadhi hui ya chipki hui hain
        val stacked = rawPucks.any { p2 ->
            p1.id != p2.id && p1.pos.dist(p2.pos) < (puckRadius * 1.75f)
        }
        p1.copy(isStacked = stacked)
    }
}