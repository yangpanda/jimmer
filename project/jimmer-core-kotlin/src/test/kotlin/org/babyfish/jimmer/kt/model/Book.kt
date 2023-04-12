package org.babyfish.jimmer.kt.model

import org.babyfish.jimmer.Immutable
import org.babyfish.jimmer.sql.IdView
import java.math.BigDecimal
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Positive
import javax.validation.constraints.Size

@Immutable
interface Book {

    @get:NotBlank
    @get:Size(min = 1, max = 50)
    val name: String

    val edition: Int

    @get:Positive
    val price: BigDecimal

    val store: BookStore?

    val authors: List<Author>

    @IdView
    val storeId: Long?

    @IdView("authors")
    val authorIds: List<Long>
}