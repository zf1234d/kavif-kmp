// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package org.glavo.avif.internal.av1.entropy

import org.glavo.avif.internal.compat.*
import kotlin.math.max
import kotlin.math.min


/** qcat-indexed coefficient CDF defaults copied from `dav1d`. */
internal object CoefficientCdfDefaults {
    /** The number of AV1 coefficient qcat bands. */
    private const val QCAT_COUNT = 4

    /** The transformed qcat-indexed default coefficient-skip CDFs. */
    private val COEFFICIENT_SKIP_CDFS_BY_QCAT: Array<Array<Array<IntArray>>> =
        initCoefficientSkipCdfsByQcat()

    /** The transformed qcat-indexed default end-of-block prefix CDFs. */
    private val END_OF_BLOCK_PREFIX_CDFS_BY_QCAT: Array<Array<Array<Array<IntArray>>>> =
        initEndOfBlockPrefixCdfsByQcat()

    /** The transformed qcat-indexed default end-of-block base-token CDFs. */
    private val END_OF_BLOCK_BASE_TOKEN_CDFS_BY_QCAT: Array<Array<Array<Array<IntArray>>>> =
        initEndOfBlockBaseTokenCdfsByQcat()

    /** The transformed qcat-indexed default end-of-block high-bit CDFs. */
    private val END_OF_BLOCK_HIGH_BIT_CDFS_BY_QCAT: Array<Array<Array<Array<IntArray>>>> =
        initEndOfBlockHighBitCdfsByQcat()

    /** The transformed qcat-indexed default coefficient base-token CDFs. */
    private val BASE_TOKEN_CDFS_BY_QCAT: Array<Array<Array<Array<IntArray>>>> =
        initBaseTokenCdfsByQcat()

    /** The transformed qcat-indexed default coefficient high-token CDFs. */
    private val HIGH_TOKEN_CDFS_BY_QCAT: Array<Array<Array<Array<IntArray>>>> =
        initHighTokenCdfsByQcat()

    /** Initializes `COEFFICIENT_SKIP_CDFS_BY_QCAT` outside the class initializer method-size limit.
     * 
     * @return the initialized `COEFFICIENT_SKIP_CDFS_BY_QCAT` table
     */
    private fun initCoefficientSkipCdfsByQcat(): Array<Array<Array<IntArray>>> {
        return CoefficientCdfDefaults.inverse4d(
            arrayOf<Array<Array<IntArray>>>(
                arrayOf<Array<IntArray>>(
                    arrayOf<IntArray>(
                        intArrayOf(31849),
                        intArrayOf(5892),
                        intArrayOf(12112),
                        intArrayOf(21935),
                        intArrayOf(20289),
                        intArrayOf(27473),
                        intArrayOf(32487),
                        intArrayOf(7654),
                        intArrayOf(19473),
                        intArrayOf(29984),
                        intArrayOf(9961),
                        intArrayOf(30242),
                        intArrayOf(32117)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(31548),
                        intArrayOf(1549),
                        intArrayOf(10130),
                        intArrayOf(16656),
                        intArrayOf(18591),
                        intArrayOf(26308),
                        intArrayOf(32537),
                        intArrayOf(5403),
                        intArrayOf(18096),
                        intArrayOf(30003),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(29957),
                        intArrayOf(5391),
                        intArrayOf(18039),
                        intArrayOf(23566),
                        intArrayOf(22431),
                        intArrayOf(25822),
                        intArrayOf(32197),
                        intArrayOf(3778),
                        intArrayOf(15336),
                        intArrayOf(28981),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(17920),
                        intArrayOf(1818),
                        intArrayOf(7282),
                        intArrayOf(25273),
                        intArrayOf(10923),
                        intArrayOf(31554),
                        intArrayOf(32624),
                        intArrayOf(1366),
                        intArrayOf(15628),
                        intArrayOf(30462),
                        intArrayOf(146),
                        intArrayOf(5132),
                        intArrayOf(31657)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(6308),
                        intArrayOf(117),
                        intArrayOf(1638),
                        intArrayOf(2161),
                        intArrayOf(16384),
                        intArrayOf(10923),
                        intArrayOf(30247),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384)
                    )
                ),
                arrayOf<Array<IntArray>>(
                    arrayOf<IntArray>(
                        intArrayOf(30371),
                        intArrayOf(7570),
                        intArrayOf(13155),
                        intArrayOf(20751),
                        intArrayOf(20969),
                        intArrayOf(27067),
                        intArrayOf(32013),
                        intArrayOf(5495),
                        intArrayOf(17942),
                        intArrayOf(28280),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(31782),
                        intArrayOf(1836),
                        intArrayOf(10689),
                        intArrayOf(17604),
                        intArrayOf(21622),
                        intArrayOf(27518),
                        intArrayOf(32399),
                        intArrayOf(4419),
                        intArrayOf(16294),
                        intArrayOf(28345),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(31901),
                        intArrayOf(10311),
                        intArrayOf(18047),
                        intArrayOf(24806),
                        intArrayOf(23288),
                        intArrayOf(27914),
                        intArrayOf(32296),
                        intArrayOf(4215),
                        intArrayOf(15756),
                        intArrayOf(28341),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(26726),
                        intArrayOf(1045),
                        intArrayOf(11703),
                        intArrayOf(20590),
                        intArrayOf(18554),
                        intArrayOf(25970),
                        intArrayOf(31938),
                        intArrayOf(5583),
                        intArrayOf(21313),
                        intArrayOf(29390),
                        intArrayOf(641),
                        intArrayOf(22265),
                        intArrayOf(31452)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(26584),
                        intArrayOf(188),
                        intArrayOf(8847),
                        intArrayOf(24519),
                        intArrayOf(22938),
                        intArrayOf(30583),
                        intArrayOf(32608),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384)
                    )
                ),
                arrayOf<Array<IntArray>>(
                    arrayOf<IntArray>(
                        intArrayOf(29614),
                        intArrayOf(9068),
                        intArrayOf(12924),
                        intArrayOf(19538),
                        intArrayOf(17737),
                        intArrayOf(24619),
                        intArrayOf(30642),
                        intArrayOf(4119),
                        intArrayOf(16026),
                        intArrayOf(25657),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(31957),
                        intArrayOf(3230),
                        intArrayOf(11153),
                        intArrayOf(18123),
                        intArrayOf(20143),
                        intArrayOf(26536),
                        intArrayOf(31986),
                        intArrayOf(3050),
                        intArrayOf(14603),
                        intArrayOf(25155),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(32363),
                        intArrayOf(10692),
                        intArrayOf(19090),
                        intArrayOf(24357),
                        intArrayOf(24442),
                        intArrayOf(28312),
                        intArrayOf(32169),
                        intArrayOf(3648),
                        intArrayOf(15690),
                        intArrayOf(26815),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(30669),
                        intArrayOf(3832),
                        intArrayOf(11663),
                        intArrayOf(18889),
                        intArrayOf(19782),
                        intArrayOf(23313),
                        intArrayOf(31330),
                        intArrayOf(5124),
                        intArrayOf(18719),
                        intArrayOf(28468),
                        intArrayOf(3082),
                        intArrayOf(20982),
                        intArrayOf(29443)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(28573),
                        intArrayOf(3183),
                        intArrayOf(17802),
                        intArrayOf(25977),
                        intArrayOf(26677),
                        intArrayOf(27832),
                        intArrayOf(32387),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384)
                    )
                ),
                arrayOf<Array<IntArray>>(
                    arrayOf<IntArray>(
                        intArrayOf(26887),
                        intArrayOf(6729),
                        intArrayOf(10361),
                        intArrayOf(17442),
                        intArrayOf(15045),
                        intArrayOf(22478),
                        intArrayOf(29072),
                        intArrayOf(2713),
                        intArrayOf(11861),
                        intArrayOf(20773),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(31903),
                        intArrayOf(2044),
                        intArrayOf(7528),
                        intArrayOf(14618),
                        intArrayOf(16182),
                        intArrayOf(24168),
                        intArrayOf(31037),
                        intArrayOf(2786),
                        intArrayOf(11194),
                        intArrayOf(20155),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(32510),
                        intArrayOf(8430),
                        intArrayOf(17318),
                        intArrayOf(24154),
                        intArrayOf(23674),
                        intArrayOf(28789),
                        intArrayOf(32139),
                        intArrayOf(3440),
                        intArrayOf(13117),
                        intArrayOf(22702),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(31671),
                        intArrayOf(2056),
                        intArrayOf(11746),
                        intArrayOf(16852),
                        intArrayOf(18635),
                        intArrayOf(24715),
                        intArrayOf(31484),
                        intArrayOf(4656),
                        intArrayOf(16074),
                        intArrayOf(24704),
                        intArrayOf(1806),
                        intArrayOf(14645),
                        intArrayOf(25336)
                    ),
                    arrayOf<IntArray>(
                        intArrayOf(31539),
                        intArrayOf(8433),
                        intArrayOf(20576),
                        intArrayOf(27904),
                        intArrayOf(27852),
                        intArrayOf(30026),
                        intArrayOf(32441),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384),
                        intArrayOf(16384)
                    )
                )
            )
        )
    }

    /** Initializes `END_OF_BLOCK_PREFIX_CDFS_BY_QCAT` outside the class initializer method-size limit.
     * 
     * @return the initialized `END_OF_BLOCK_PREFIX_CDFS_BY_QCAT` table
     */
    private fun initEndOfBlockPrefixCdfsByQcat(): Array<Array<Array<Array<IntArray>>>> {
        return CoefficientCdfDefaults.inverse5d(
            arrayOf<Array<Array<Array<IntArray>>>>(
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(840, 1039, 1980, 4895),
                            intArrayOf(370, 671, 1883, 4471)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(3247, 4950, 9688, 14563),
                            intArrayOf(1904, 3354, 7763, 14647)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(400, 520, 977, 2102, 6542),
                            intArrayOf(210, 405, 1315, 3326, 7537)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(2636, 4273, 7588, 11794, 20401),
                            intArrayOf(1786, 3179, 6902, 11357, 19054)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(329, 498, 1101, 1784, 3265, 7758),
                            intArrayOf(335, 730, 1459, 5494, 8755, 12997)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(3505, 5304, 10086, 13814, 17684, 23370),
                            intArrayOf(1563, 2700, 4876, 10911, 14706, 22480)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(219, 482, 1140, 2091, 3680, 6028, 12586),
                            intArrayOf(371, 699, 1254, 4830, 9479, 12562, 17497)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(5245, 7456, 12880, 15852, 20033, 23932, 27608),
                            intArrayOf(2054, 3472, 5869, 14232, 18242, 20590, 26752)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(310, 584, 1887, 3589, 6168, 8611, 11352, 15652),
                            intArrayOf(998, 1850, 2998, 5604, 17341, 19888, 22899, 25583)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(2520, 3240, 5952, 8870, 12577, 17558, 19954, 24168),
                            intArrayOf(2203, 4130, 7435, 10739, 20652, 23681, 25609, 27261)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(641, 983, 3707, 5430, 10234, 14958, 18788, 23412, 26061),
                            intArrayOf(641, 983, 3707, 5430, 10234, 14958, 18788, 23412, 26061)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(5095, 6446, 9996, 13354, 16017, 17986, 20919, 26129, 29140),
                            intArrayOf(5095, 6446, 9996, 13354, 16017, 17986, 20919, 26129, 29140)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(393, 421, 751, 1623, 3160, 6352, 13345, 18047, 22571, 25830),
                            intArrayOf(393, 421, 751, 1623, 3160, 6352, 13345, 18047, 22571, 25830)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(
                                1865,
                                1988,
                                2930,
                                4242,
                                10533,
                                16538,
                                21354,
                                27255,
                                28546,
                                31784
                            ),
                            intArrayOf(
                                1865,
                                1988,
                                2930,
                                4242,
                                10533,
                                16538,
                                21354,
                                27255,
                                28546,
                                31784
                            )
                        )
                    )
                ),
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(2125, 2551, 5165, 8946),
                            intArrayOf(513, 765, 1859, 6339)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(7637, 9498, 14259, 19108),
                            intArrayOf(2497, 4096, 8866, 16993)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(989, 1249, 2019, 4151, 10785),
                            intArrayOf(313, 441, 1099, 2917, 8562)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(8394, 10352, 13932, 18855, 26014),
                            intArrayOf(2578, 4124, 8181, 13670, 24234)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(1260, 1446, 2253, 3712, 6652, 13369),
                            intArrayOf(401, 605, 1029, 2563, 5845, 12626)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(8609, 10612, 14624, 18714, 22614, 29024),
                            intArrayOf(1923, 3127, 5867, 9703, 14277, 27100)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(685, 933, 1488, 2714, 4766, 8562, 19254),
                            intArrayOf(217, 352, 618, 2303, 5261, 9969, 17472)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(8045, 11200, 15497, 19595, 23948, 27408, 30938),
                            intArrayOf(2310, 4160, 7471, 14997, 17931, 20768, 30240)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(1448, 2109, 4151, 6263, 9329, 13260, 17944, 23300),
                            intArrayOf(399, 1019, 1749, 3038, 10444, 15546, 22739, 27294)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(6402, 8148, 12623, 15072, 18728, 22847, 26447, 29377),
                            intArrayOf(1674, 3252, 5734, 10159, 22397, 23802, 24821, 30940)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(1230, 2278, 5035, 7776, 11871, 15346, 19590, 24584, 28749),
                            intArrayOf(1230, 2278, 5035, 7776, 11871, 15346, 19590, 24584, 28749)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(7265, 9979, 15819, 19250, 21780, 23846, 26478, 28396, 31811),
                            intArrayOf(7265, 9979, 15819, 19250, 21780, 23846, 26478, 28396, 31811)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(
                                696,
                                948,
                                3145,
                                5702,
                                9706,
                                13217,
                                17851,
                                21856,
                                25692,
                                28034
                            ),
                            intArrayOf(
                                696,
                                948,
                                3145,
                                5702,
                                9706,
                                13217,
                                17851,
                                21856,
                                25692,
                                28034
                            )
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(
                                2672,
                                3591,
                                9330,
                                17084,
                                22725,
                                24284,
                                26527,
                                28027,
                                28377,
                                30876
                            ),
                            intArrayOf(
                                2672,
                                3591,
                                9330,
                                17084,
                                22725,
                                24284,
                                26527,
                                28027,
                                28377,
                                30876
                            )
                        )
                    )
                ),
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(4016, 4897, 8881, 14968),
                            intArrayOf(716, 1105, 2646, 10056)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(11139, 13270, 18241, 23566),
                            intArrayOf(3192, 5032, 10297, 19755)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(2515, 3003, 4452, 8162, 16041),
                            intArrayOf(574, 821, 1836, 5089, 13128)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(13468, 16303, 20361, 25105, 29281),
                            intArrayOf(3542, 5502, 10415, 16760, 25644)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(2374, 2772, 4583, 7276, 12288, 19706),
                            intArrayOf(497, 810, 1315, 3000, 7004, 15641)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(15050, 17126, 21410, 24886, 28156, 30726),
                            intArrayOf(4034, 6290, 10235, 14982, 21214, 28491)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(1366, 1738, 2527, 5016, 9355, 15797, 24643),
                            intArrayOf(354, 558, 944, 2760, 7287, 14037, 21779)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(13627, 16246, 20173, 24429, 27948, 30415, 31863),
                            intArrayOf(6275, 9889, 14769, 23164, 27988, 30493, 32272)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(3089, 3920, 6038, 9460, 14266, 19881, 25766, 29176),
                            intArrayOf(1084, 2358, 3488, 5122, 11483, 18103, 26023, 29799)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(11514, 13794, 17480, 20754, 24361, 27378, 29492, 31277),
                            intArrayOf(6571, 9610, 15516, 21826, 29092, 30829, 31842, 32708)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(2624, 3936, 6480, 9686, 13979, 17726, 23267, 28410, 31078),
                            intArrayOf(2624, 3936, 6480, 9686, 13979, 17726, 23267, 28410, 31078)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(
                                12015,
                                14769,
                                19588,
                                22052,
                                24222,
                                25812,
                                27300,
                                29219,
                                32114
                            ),
                            intArrayOf(
                                12015,
                                14769,
                                19588,
                                22052,
                                24222,
                                25812,
                                27300,
                                29219,
                                32114
                            )
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(
                                2784,
                                3831,
                                7041,
                                10521,
                                14847,
                                18844,
                                23155,
                                26682,
                                29229,
                                31045
                            ),
                            intArrayOf(
                                2784,
                                3831,
                                7041,
                                10521,
                                14847,
                                18844,
                                23155,
                                26682,
                                29229,
                                31045
                            )
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(
                                9577,
                                12466,
                                17739,
                                20750,
                                22061,
                                23215,
                                24601,
                                25483,
                                25843,
                                32056
                            ),
                            intArrayOf(
                                9577,
                                12466,
                                17739,
                                20750,
                                22061,
                                23215,
                                24601,
                                25483,
                                25843,
                                32056
                            )
                        )
                    )
                ),
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(6708, 8958, 14746, 22133),
                            intArrayOf(1222, 2074, 4783, 15410)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(19575, 21766, 26044, 29709),
                            intArrayOf(7297, 10767, 19273, 28194)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(4617, 5709, 8446, 13584, 23135),
                            intArrayOf(1156, 1702, 3675, 9274, 20539)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(22086, 24282, 27010, 29770, 31743),
                            intArrayOf(7699, 10897, 20891, 26926, 31628)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(6307, 7541, 12060, 16358, 22553, 27865),
                            intArrayOf(1289, 2320, 3971, 7926, 14153, 24291)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(24212, 25708, 28268, 30035, 31307, 32049),
                            intArrayOf(8726, 12378, 19409, 26450, 30038, 32462)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(3472, 4885, 7489, 12481, 18517, 24536, 29635),
                            intArrayOf(886, 1731, 3271, 8469, 15569, 22126, 28383)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(24313, 26062, 28385, 30107, 31217, 31898, 32345),
                            intArrayOf(9165, 13282, 21150, 30286, 31894, 32571, 32712)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(5348, 7113, 11820, 15924, 22106, 26777, 30334, 31757),
                            intArrayOf(2453, 4474, 6307, 8777, 16474, 22975, 29000, 31547)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(23110, 24597, 27140, 28894, 30167, 30927, 31392, 32094),
                            intArrayOf(9998, 17661, 25178, 28097, 31308, 32038, 32403, 32695)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(5927, 7809, 10923, 14597, 19439, 24135, 28456, 31142, 32060),
                            intArrayOf(5927, 7809, 10923, 14597, 19439, 24135, 28456, 31142, 32060)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(
                                21093,
                                23043,
                                25742,
                                27658,
                                29097,
                                29716,
                                30073,
                                30820,
                                31956
                            ),
                            intArrayOf(
                                21093,
                                23043,
                                25742,
                                27658,
                                29097,
                                29716,
                                30073,
                                30820,
                                31956
                            )
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(
                                6698,
                                8334,
                                11961,
                                15762,
                                20186,
                                23862,
                                27434,
                                29326,
                                31082,
                                32050
                            ),
                            intArrayOf(
                                6698,
                                8334,
                                11961,
                                15762,
                                20186,
                                23862,
                                27434,
                                29326,
                                31082,
                                32050
                            )
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(
                                20569,
                                22426,
                                25569,
                                26859,
                                28053,
                                28913,
                                29486,
                                29724,
                                29807,
                                32570
                            ),
                            intArrayOf(
                                20569,
                                22426,
                                25569,
                                26859,
                                28053,
                                28913,
                                29486,
                                29724,
                                29807,
                                32570
                            )
                        )
                    )
                )
            )
        )
    }

    /** Initializes `END_OF_BLOCK_BASE_TOKEN_CDFS_BY_QCAT` outside the class initializer method-size limit.
     * 
     * @return the initialized `END_OF_BLOCK_BASE_TOKEN_CDFS_BY_QCAT` table
     */
    private fun initEndOfBlockBaseTokenCdfsByQcat(): Array<Array<Array<Array<IntArray>>>> {
        return CoefficientCdfDefaults.inverse5d(
            arrayOf<Array<Array<Array<IntArray>>>>(
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(17837, 29055),
                            intArrayOf(29600, 31446),
                            intArrayOf(30844, 31878),
                            intArrayOf(24926, 28948)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(21365, 30026),
                            intArrayOf(30512, 32423),
                            intArrayOf(31658, 32621),
                            intArrayOf(29630, 31881)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(5717, 26477),
                            intArrayOf(30491, 31703),
                            intArrayOf(31550, 32158),
                            intArrayOf(29648, 31491)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(12608, 27820),
                            intArrayOf(30680, 32225),
                            intArrayOf(30809, 32335),
                            intArrayOf(31299, 32423)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(1786, 12612),
                            intArrayOf(30663, 31625),
                            intArrayOf(32339, 32468),
                            intArrayOf(31148, 31833)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(18857, 23865),
                            intArrayOf(31428, 32428),
                            intArrayOf(31744, 32373),
                            intArrayOf(31775, 32526)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(1787, 2532),
                            intArrayOf(30832, 31662),
                            intArrayOf(31824, 32682),
                            intArrayOf(32133, 32569)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(13751, 22235),
                            intArrayOf(32089, 32409),
                            intArrayOf(27084, 27920),
                            intArrayOf(29291, 32594)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(1725, 3449),
                            intArrayOf(31102, 31935),
                            intArrayOf(32457, 32613),
                            intArrayOf(32412, 32649)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(10923, 21845),
                            intArrayOf(10923, 21845),
                            intArrayOf(10923, 21845),
                            intArrayOf(10923, 21845)
                        )
                    )
                ),
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(17560, 29888),
                            intArrayOf(29671, 31549),
                            intArrayOf(31007, 32056),
                            intArrayOf(27286, 30006)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(26594, 31212),
                            intArrayOf(31208, 32582),
                            intArrayOf(31835, 32637),
                            intArrayOf(30595, 32206)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(15239, 29932),
                            intArrayOf(31315, 32095),
                            intArrayOf(32130, 32434),
                            intArrayOf(30864, 31996)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(26279, 30968),
                            intArrayOf(31142, 32495),
                            intArrayOf(31713, 32540),
                            intArrayOf(31929, 32594)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(2644, 25198),
                            intArrayOf(32038, 32451),
                            intArrayOf(32639, 32695),
                            intArrayOf(32166, 32518)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(17187, 27668),
                            intArrayOf(31714, 32550),
                            intArrayOf(32283, 32678),
                            intArrayOf(31930, 32563)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(1044, 2257),
                            intArrayOf(30755, 31923),
                            intArrayOf(32208, 32693),
                            intArrayOf(32244, 32615)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(21317, 26207),
                            intArrayOf(29133, 30868),
                            intArrayOf(29311, 31231),
                            intArrayOf(29657, 31087)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(478, 1834),
                            intArrayOf(31005, 31987),
                            intArrayOf(32317, 32724),
                            intArrayOf(30865, 32648)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(10923, 21845),
                            intArrayOf(10923, 21845),
                            intArrayOf(10923, 21845),
                            intArrayOf(10923, 21845)
                        )
                    )
                ),
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(20092, 30774),
                            intArrayOf(30695, 32020),
                            intArrayOf(31131, 32103),
                            intArrayOf(28666, 30870)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(27258, 31095),
                            intArrayOf(31804, 32623),
                            intArrayOf(31763, 32528),
                            intArrayOf(31438, 32506)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(18049, 30489),
                            intArrayOf(31706, 32286),
                            intArrayOf(32163, 32473),
                            intArrayOf(31550, 32184)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(27116, 30842),
                            intArrayOf(31971, 32598),
                            intArrayOf(32088, 32576),
                            intArrayOf(32067, 32664)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(12854, 29093),
                            intArrayOf(32272, 32558),
                            intArrayOf(32667, 32729),
                            intArrayOf(32306, 32585)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(25476, 30366),
                            intArrayOf(32169, 32687),
                            intArrayOf(32479, 32689),
                            intArrayOf(31673, 32634)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(2809, 19301),
                            intArrayOf(32205, 32622),
                            intArrayOf(32338, 32730),
                            intArrayOf(31786, 32616)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(22737, 29105),
                            intArrayOf(30810, 32362),
                            intArrayOf(30014, 32627),
                            intArrayOf(30528, 32574)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(935, 3382),
                            intArrayOf(30789, 31909),
                            intArrayOf(32466, 32756),
                            intArrayOf(30860, 32513)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(10923, 21845),
                            intArrayOf(10923, 21845),
                            intArrayOf(10923, 21845),
                            intArrayOf(10923, 21845)
                        )
                    )
                ),
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(22497, 31198),
                            intArrayOf(31715, 32495),
                            intArrayOf(31606, 32337),
                            intArrayOf(30388, 31990)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(27877, 31584),
                            intArrayOf(32170, 32728),
                            intArrayOf(32155, 32688),
                            intArrayOf(32219, 32702)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(21457, 31043),
                            intArrayOf(31951, 32483),
                            intArrayOf(32153, 32562),
                            intArrayOf(31473, 32215)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(27558, 31151),
                            intArrayOf(32020, 32640),
                            intArrayOf(32097, 32575),
                            intArrayOf(32242, 32719)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(19980, 30591),
                            intArrayOf(32219, 32597),
                            intArrayOf(32581, 32706),
                            intArrayOf(31803, 32287)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(26473, 30507),
                            intArrayOf(32431, 32723),
                            intArrayOf(32196, 32611),
                            intArrayOf(31588, 32528)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(24647, 30463),
                            intArrayOf(32412, 32695),
                            intArrayOf(32468, 32720),
                            intArrayOf(31269, 32523)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(28482, 31505),
                            intArrayOf(32152, 32701),
                            intArrayOf(31732, 32598),
                            intArrayOf(31767, 32712)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(12358, 24977),
                            intArrayOf(31331, 32385),
                            intArrayOf(32634, 32756),
                            intArrayOf(30411, 32548)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(10923, 21845),
                            intArrayOf(10923, 21845),
                            intArrayOf(10923, 21845),
                            intArrayOf(10923, 21845)
                        )
                    )
                )
            )
        )
    }

    /** Initializes `END_OF_BLOCK_HIGH_BIT_CDFS_BY_QCAT` outside the class initializer method-size limit.
     * 
     * @return the initialized `END_OF_BLOCK_HIGH_BIT_CDFS_BY_QCAT` table
     */
    private fun initEndOfBlockHighBitCdfsByQcat(): Array<Array<Array<Array<IntArray>>>> {
        return CoefficientCdfDefaults.inverse5d(
            arrayOf<Array<Array<Array<IntArray>>>>(
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(16961),
                            intArrayOf(17223),
                            intArrayOf(7621),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(19069),
                            intArrayOf(22525),
                            intArrayOf(13377),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(20401),
                            intArrayOf(17025),
                            intArrayOf(12845),
                            intArrayOf(12873),
                            intArrayOf(14094),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(20681),
                            intArrayOf(20701),
                            intArrayOf(15250),
                            intArrayOf(15017),
                            intArrayOf(14928),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(23905),
                            intArrayOf(17194),
                            intArrayOf(16170),
                            intArrayOf(17695),
                            intArrayOf(13826),
                            intArrayOf(15810),
                            intArrayOf(12036),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(23959),
                            intArrayOf(20799),
                            intArrayOf(19021),
                            intArrayOf(16203),
                            intArrayOf(17886),
                            intArrayOf(14144),
                            intArrayOf(12010),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(27399),
                            intArrayOf(16327),
                            intArrayOf(18071),
                            intArrayOf(19584),
                            intArrayOf(20721),
                            intArrayOf(18432),
                            intArrayOf(19560),
                            intArrayOf(10150),
                            intArrayOf(8805)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(24932),
                            intArrayOf(20833),
                            intArrayOf(12027),
                            intArrayOf(16670),
                            intArrayOf(19914),
                            intArrayOf(15106),
                            intArrayOf(17662),
                            intArrayOf(13783),
                            intArrayOf(28756)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(23406),
                            intArrayOf(21845),
                            intArrayOf(18432),
                            intArrayOf(16384),
                            intArrayOf(17096),
                            intArrayOf(12561),
                            intArrayOf(17320),
                            intArrayOf(22395),
                            intArrayOf(21370)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        )
                    )
                ),
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(17471),
                            intArrayOf(20223),
                            intArrayOf(11357),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(20335),
                            intArrayOf(21667),
                            intArrayOf(14818),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(20430),
                            intArrayOf(20662),
                            intArrayOf(15367),
                            intArrayOf(16970),
                            intArrayOf(14657),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(22117),
                            intArrayOf(22028),
                            intArrayOf(18650),
                            intArrayOf(16042),
                            intArrayOf(15885),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(22409),
                            intArrayOf(21012),
                            intArrayOf(15650),
                            intArrayOf(17395),
                            intArrayOf(15469),
                            intArrayOf(20205),
                            intArrayOf(19511),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(24220),
                            intArrayOf(22480),
                            intArrayOf(17737),
                            intArrayOf(18916),
                            intArrayOf(19268),
                            intArrayOf(18412),
                            intArrayOf(18844),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(25991),
                            intArrayOf(20314),
                            intArrayOf(17731),
                            intArrayOf(19678),
                            intArrayOf(18649),
                            intArrayOf(17307),
                            intArrayOf(21798),
                            intArrayOf(17549),
                            intArrayOf(15630)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(26585),
                            intArrayOf(21469),
                            intArrayOf(20432),
                            intArrayOf(17735),
                            intArrayOf(19280),
                            intArrayOf(15235),
                            intArrayOf(20297),
                            intArrayOf(22471),
                            intArrayOf(28997)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(26605),
                            intArrayOf(11304),
                            intArrayOf(16726),
                            intArrayOf(16560),
                            intArrayOf(20866),
                            intArrayOf(23524),
                            intArrayOf(19878),
                            intArrayOf(13469),
                            intArrayOf(23084)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        )
                    )
                ),
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(18983),
                            intArrayOf(20512),
                            intArrayOf(14885),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(20090),
                            intArrayOf(19444),
                            intArrayOf(17286),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(19139),
                            intArrayOf(21487),
                            intArrayOf(18959),
                            intArrayOf(20910),
                            intArrayOf(19089),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(20536),
                            intArrayOf(20664),
                            intArrayOf(20625),
                            intArrayOf(19123),
                            intArrayOf(14862),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(19833),
                            intArrayOf(21502),
                            intArrayOf(17485),
                            intArrayOf(20267),
                            intArrayOf(18353),
                            intArrayOf(23329),
                            intArrayOf(21478),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(22041),
                            intArrayOf(23434),
                            intArrayOf(20001),
                            intArrayOf(20554),
                            intArrayOf(20951),
                            intArrayOf(20145),
                            intArrayOf(15562),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(23312),
                            intArrayOf(21607),
                            intArrayOf(16526),
                            intArrayOf(18957),
                            intArrayOf(18034),
                            intArrayOf(18934),
                            intArrayOf(24247),
                            intArrayOf(16921),
                            intArrayOf(17080)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(26579),
                            intArrayOf(24910),
                            intArrayOf(18637),
                            intArrayOf(19800),
                            intArrayOf(20388),
                            intArrayOf(9887),
                            intArrayOf(15642),
                            intArrayOf(30198),
                            intArrayOf(24721)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(26998),
                            intArrayOf(16737),
                            intArrayOf(17838),
                            intArrayOf(18922),
                            intArrayOf(19515),
                            intArrayOf(18636),
                            intArrayOf(17333),
                            intArrayOf(15776),
                            intArrayOf(22658)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        )
                    )
                ),
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(20177),
                            intArrayOf(20789),
                            intArrayOf(20262),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(21416),
                            intArrayOf(20855),
                            intArrayOf(23410),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(20238),
                            intArrayOf(21057),
                            intArrayOf(19159),
                            intArrayOf(22337),
                            intArrayOf(20159),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(20125),
                            intArrayOf(20559),
                            intArrayOf(21707),
                            intArrayOf(22296),
                            intArrayOf(17333),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(19941),
                            intArrayOf(20527),
                            intArrayOf(21470),
                            intArrayOf(22487),
                            intArrayOf(19558),
                            intArrayOf(22354),
                            intArrayOf(20331),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(22752),
                            intArrayOf(25006),
                            intArrayOf(22075),
                            intArrayOf(21576),
                            intArrayOf(17740),
                            intArrayOf(21690),
                            intArrayOf(19211),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(21442),
                            intArrayOf(22358),
                            intArrayOf(18503),
                            intArrayOf(20291),
                            intArrayOf(19945),
                            intArrayOf(21294),
                            intArrayOf(21178),
                            intArrayOf(19400),
                            intArrayOf(10556)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(24648),
                            intArrayOf(24949),
                            intArrayOf(20708),
                            intArrayOf(23905),
                            intArrayOf(20501),
                            intArrayOf(9558),
                            intArrayOf(9423),
                            intArrayOf(30365),
                            intArrayOf(19253)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(26064),
                            intArrayOf(22098),
                            intArrayOf(19613),
                            intArrayOf(20525),
                            intArrayOf(17595),
                            intArrayOf(16618),
                            intArrayOf(20497),
                            intArrayOf(18989),
                            intArrayOf(15513)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384),
                            intArrayOf(16384)
                        )
                    )
                )
            )
        )
    }

    /** Initializes `BASE_TOKEN_CDFS_BY_QCAT` outside the class initializer method-size limit.
     * 
     * @return the initialized `BASE_TOKEN_CDFS_BY_QCAT` table
     */
    private fun initBaseTokenCdfsByQcat(): Array<Array<Array<Array<IntArray>>>> {
        return CoefficientCdfDefaults.inverse5d(
            arrayOf<Array<Array<Array<IntArray>>>>(
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(4034, 8930, 12727),
                            intArrayOf(18082, 29741, 31877),
                            intArrayOf(12596, 26124, 30493),
                            intArrayOf(9446, 21118, 27005),
                            intArrayOf(6308, 15141, 21279),
                            intArrayOf(2463, 6357, 9783),
                            intArrayOf(20667, 30546, 31929),
                            intArrayOf(13043, 26123, 30134),
                            intArrayOf(8151, 18757, 24778),
                            intArrayOf(5255, 12839, 18632),
                            intArrayOf(2820, 7206, 11161),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(15736, 27553, 30604),
                            intArrayOf(11210, 23794, 28787),
                            intArrayOf(5947, 13874, 19701),
                            intArrayOf(4215, 9323, 13891),
                            intArrayOf(2833, 6462, 10059),
                            intArrayOf(19605, 30393, 31582),
                            intArrayOf(13523, 26252, 30248),
                            intArrayOf(8446, 18622, 24512),
                            intArrayOf(3818, 10343, 15974),
                            intArrayOf(1481, 4117, 6796),
                            intArrayOf(22649, 31302, 32190),
                            intArrayOf(14829, 27127, 30449),
                            intArrayOf(8313, 17702, 23304),
                            intArrayOf(3022, 8301, 12786),
                            intArrayOf(1536, 4412, 7184),
                            intArrayOf(22354, 29774, 31372),
                            intArrayOf(14723, 25472, 29214),
                            intArrayOf(6673, 13745, 18662),
                            intArrayOf(2068, 5766, 9322),
                            intArrayOf(8192, 16384, 24576)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(6302, 16444, 21761),
                            intArrayOf(23040, 31538, 32475),
                            intArrayOf(15196, 28452, 31496),
                            intArrayOf(10020, 22946, 28514),
                            intArrayOf(6533, 16862, 23501),
                            intArrayOf(3538, 9816, 15076),
                            intArrayOf(24444, 31875, 32525),
                            intArrayOf(15881, 28924, 31635),
                            intArrayOf(9922, 22873, 28466),
                            intArrayOf(6527, 16966, 23691),
                            intArrayOf(4114, 11303, 17220),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(20201, 30770, 32209),
                            intArrayOf(14754, 28071, 31258),
                            intArrayOf(8378, 20186, 26517),
                            intArrayOf(5916, 15299, 21978),
                            intArrayOf(4268, 11583, 17901),
                            intArrayOf(24361, 32025, 32581),
                            intArrayOf(18673, 30105, 31943),
                            intArrayOf(10196, 22244, 27576),
                            intArrayOf(5495, 14349, 20417),
                            intArrayOf(2676, 7415, 11498),
                            intArrayOf(24678, 31958, 32585),
                            intArrayOf(18629, 29906, 31831),
                            intArrayOf(9364, 20724, 26315),
                            intArrayOf(4641, 12318, 18094),
                            intArrayOf(2758, 7387, 11579),
                            intArrayOf(25433, 31842, 32469),
                            intArrayOf(18795, 29289, 31411),
                            intArrayOf(7644, 17584, 23592),
                            intArrayOf(3408, 9014, 15047),
                            intArrayOf(8192, 16384, 24576)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(4536, 10072, 14001),
                            intArrayOf(25459, 31416, 32206),
                            intArrayOf(16605, 28048, 30818),
                            intArrayOf(11008, 22857, 27719),
                            intArrayOf(6915, 16268, 22315),
                            intArrayOf(2625, 6812, 10537),
                            intArrayOf(24257, 31788, 32499),
                            intArrayOf(16880, 29454, 31879),
                            intArrayOf(11958, 25054, 29778),
                            intArrayOf(7916, 18718, 25084),
                            intArrayOf(3383, 8777, 13446),
                            intArrayOf(22720, 31603, 32393),
                            intArrayOf(14960, 28125, 31335),
                            intArrayOf(9731, 22210, 27928),
                            intArrayOf(6304, 15832, 22277),
                            intArrayOf(2910, 7818, 12166),
                            intArrayOf(20375, 30627, 32131),
                            intArrayOf(13904, 27284, 30887),
                            intArrayOf(9368, 21558, 27144),
                            intArrayOf(5937, 14966, 21119),
                            intArrayOf(2667, 7225, 11319),
                            intArrayOf(23970, 31470, 32378),
                            intArrayOf(17173, 29734, 32018),
                            intArrayOf(12795, 25441, 29965),
                            intArrayOf(8981, 19680, 25893),
                            intArrayOf(4728, 11372, 16902),
                            intArrayOf(24287, 31797, 32439),
                            intArrayOf(16703, 29145, 31696),
                            intArrayOf(10833, 23554, 28725),
                            intArrayOf(6468, 16566, 23057),
                            intArrayOf(2415, 6562, 10278),
                            intArrayOf(26610, 32395, 32659),
                            intArrayOf(18590, 30498, 32117),
                            intArrayOf(12420, 25756, 29950),
                            intArrayOf(7639, 18746, 24710),
                            intArrayOf(3001, 8086, 12347),
                            intArrayOf(25076, 32064, 32580),
                            intArrayOf(17946, 30128, 32028),
                            intArrayOf(12024, 24985, 29378),
                            intArrayOf(7517, 18390, 24304),
                            intArrayOf(3243, 8781, 13331)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(6037, 16771, 21957),
                            intArrayOf(24774, 31704, 32426),
                            intArrayOf(16830, 28589, 31056),
                            intArrayOf(10602, 22828, 27760),
                            intArrayOf(6733, 16829, 23071),
                            intArrayOf(3250, 8914, 13556),
                            intArrayOf(25582, 32220, 32668),
                            intArrayOf(18659, 30342, 32223),
                            intArrayOf(12546, 26149, 30515),
                            intArrayOf(8420, 20451, 26801),
                            intArrayOf(4636, 12420, 18344),
                            intArrayOf(27581, 32362, 32639),
                            intArrayOf(18987, 30083, 31978),
                            intArrayOf(11327, 24248, 29084),
                            intArrayOf(7264, 17719, 24120),
                            intArrayOf(3995, 10768, 16169),
                            intArrayOf(25893, 31831, 32487),
                            intArrayOf(16577, 28587, 31379),
                            intArrayOf(10189, 22748, 28182),
                            intArrayOf(6832, 17094, 23556),
                            intArrayOf(3708, 10110, 15334),
                            intArrayOf(25904, 32282, 32656),
                            intArrayOf(19721, 30792, 32276),
                            intArrayOf(12819, 26243, 30411),
                            intArrayOf(8572, 20614, 26891),
                            intArrayOf(5364, 14059, 20467),
                            intArrayOf(26580, 32438, 32677),
                            intArrayOf(20852, 31225, 32340),
                            intArrayOf(12435, 25700, 29967),
                            intArrayOf(8691, 20825, 26976),
                            intArrayOf(4446, 12209, 17269),
                            intArrayOf(27350, 32429, 32696),
                            intArrayOf(21372, 30977, 32272),
                            intArrayOf(12673, 25270, 29853),
                            intArrayOf(9208, 20925, 26640),
                            intArrayOf(5018, 13351, 18732),
                            intArrayOf(27351, 32479, 32713),
                            intArrayOf(21398, 31209, 32387),
                            intArrayOf(12162, 25047, 29842),
                            intArrayOf(7896, 18691, 25319),
                            intArrayOf(4670, 12882, 18881)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(5487, 10460, 13708),
                            intArrayOf(21597, 28303, 30674),
                            intArrayOf(11037, 21953, 26476),
                            intArrayOf(8147, 17962, 22952),
                            intArrayOf(5242, 13061, 18532),
                            intArrayOf(1889, 5208, 8182),
                            intArrayOf(26774, 32133, 32590),
                            intArrayOf(17844, 29564, 31767),
                            intArrayOf(11690, 24438, 29171),
                            intArrayOf(7542, 18215, 24459),
                            intArrayOf(2993, 8050, 12319),
                            intArrayOf(28023, 32328, 32591),
                            intArrayOf(18651, 30126, 31954),
                            intArrayOf(12164, 25146, 29589),
                            intArrayOf(7762, 18530, 24771),
                            intArrayOf(3492, 9183, 13920),
                            intArrayOf(27591, 32008, 32491),
                            intArrayOf(17149, 28853, 31510),
                            intArrayOf(11485, 24003, 28860),
                            intArrayOf(7697, 18086, 24210),
                            intArrayOf(3075, 7999, 12218),
                            intArrayOf(28268, 32482, 32654),
                            intArrayOf(19631, 31051, 32404),
                            intArrayOf(13860, 27260, 31020),
                            intArrayOf(9605, 21613, 27594),
                            intArrayOf(4876, 12162, 17908),
                            intArrayOf(27248, 32316, 32576),
                            intArrayOf(18955, 30457, 32075),
                            intArrayOf(11824, 23997, 28795),
                            intArrayOf(7346, 18196, 24647),
                            intArrayOf(3403, 9247, 14111),
                            intArrayOf(29711, 32655, 32735),
                            intArrayOf(21169, 31394, 32417),
                            intArrayOf(13487, 27198, 30957),
                            intArrayOf(8828, 21683, 27614),
                            intArrayOf(4270, 11451, 17038),
                            intArrayOf(28708, 32578, 32731),
                            intArrayOf(20120, 31241, 32482),
                            intArrayOf(13692, 27550, 31321),
                            intArrayOf(9418, 22514, 28439),
                            intArrayOf(4999, 13283, 19462)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(5673, 14302, 19711),
                            intArrayOf(26251, 30701, 31834),
                            intArrayOf(12782, 23783, 27803),
                            intArrayOf(9127, 20657, 25808),
                            intArrayOf(6368, 16208, 21462),
                            intArrayOf(2465, 7177, 10822),
                            intArrayOf(29961, 32563, 32719),
                            intArrayOf(18318, 29891, 31949),
                            intArrayOf(11361, 24514, 29357),
                            intArrayOf(7900, 19603, 25607),
                            intArrayOf(4002, 10590, 15546),
                            intArrayOf(29637, 32310, 32595),
                            intArrayOf(18296, 29913, 31809),
                            intArrayOf(10144, 21515, 26871),
                            intArrayOf(5358, 14322, 20394),
                            intArrayOf(3067, 8362, 13346),
                            intArrayOf(28652, 32470, 32676),
                            intArrayOf(17538, 30771, 32209),
                            intArrayOf(13924, 26882, 30494),
                            intArrayOf(10496, 22837, 27869),
                            intArrayOf(7236, 16396, 21621),
                            intArrayOf(30743, 32687, 32746),
                            intArrayOf(23006, 31676, 32489),
                            intArrayOf(14494, 27828, 31120),
                            intArrayOf(10174, 22801, 28352),
                            intArrayOf(6242, 15281, 21043),
                            intArrayOf(25817, 32243, 32720),
                            intArrayOf(18618, 31367, 32325),
                            intArrayOf(13997, 28318, 31878),
                            intArrayOf(12255, 26534, 31383),
                            intArrayOf(9561, 21588, 28450),
                            intArrayOf(28188, 32635, 32724),
                            intArrayOf(22060, 32365, 32728),
                            intArrayOf(18102, 30690, 32528),
                            intArrayOf(14196, 28864, 31999),
                            intArrayOf(12262, 25792, 30865),
                            intArrayOf(24176, 32109, 32628),
                            intArrayOf(18280, 29681, 31963),
                            intArrayOf(10205, 23703, 29664),
                            intArrayOf(7889, 20025, 27676),
                            intArrayOf(6060, 16743, 23970)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(5141, 7096, 8260),
                            intArrayOf(27186, 29022, 29789),
                            intArrayOf(6668, 12568, 15682),
                            intArrayOf(2172, 6181, 8638),
                            intArrayOf(1126, 3379, 4531),
                            intArrayOf(443, 1361, 2254),
                            intArrayOf(26083, 31153, 32436),
                            intArrayOf(13486, 24603, 28483),
                            intArrayOf(6508, 14840, 19910),
                            intArrayOf(3386, 8800, 13286),
                            intArrayOf(1530, 4322, 7054),
                            intArrayOf(29639, 32080, 32548),
                            intArrayOf(15897, 27552, 30290),
                            intArrayOf(8588, 20047, 25383),
                            intArrayOf(4889, 13339, 19269),
                            intArrayOf(2240, 6871, 10498),
                            intArrayOf(28165, 32197, 32517),
                            intArrayOf(20735, 30427, 31568),
                            intArrayOf(14325, 24671, 27692),
                            intArrayOf(5119, 12554, 17805),
                            intArrayOf(1810, 5441, 8261),
                            intArrayOf(31212, 32724, 32748),
                            intArrayOf(23352, 31766, 32545),
                            intArrayOf(14669, 27570, 31059),
                            intArrayOf(8492, 20894, 27272),
                            intArrayOf(3644, 10194, 15204),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(2461, 7013, 9371),
                            intArrayOf(24749, 29600, 30986),
                            intArrayOf(9466, 19037, 22417),
                            intArrayOf(3584, 9280, 14400),
                            intArrayOf(1505, 3929, 5433),
                            intArrayOf(677, 1500, 2736),
                            intArrayOf(23987, 30702, 32117),
                            intArrayOf(13554, 24571, 29263),
                            intArrayOf(6211, 14556, 21155),
                            intArrayOf(3135, 10972, 15625),
                            intArrayOf(2435, 7127, 11427),
                            intArrayOf(31300, 32532, 32550),
                            intArrayOf(14757, 30365, 31954),
                            intArrayOf(4405, 11612, 18553),
                            intArrayOf(580, 4132, 7322),
                            intArrayOf(1695, 10169, 14124),
                            intArrayOf(30008, 32282, 32591),
                            intArrayOf(19244, 30108, 31748),
                            intArrayOf(11180, 24158, 29555),
                            intArrayOf(5650, 14972, 19209),
                            intArrayOf(2114, 5109, 8456),
                            intArrayOf(31856, 32716, 32748),
                            intArrayOf(23012, 31664, 32572),
                            intArrayOf(13694, 26656, 30636),
                            intArrayOf(8142, 19508, 26093),
                            intArrayOf(4253, 10955, 16724),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(601, 983, 1311),
                            intArrayOf(18725, 23406, 28087),
                            intArrayOf(5461, 8192, 10923),
                            intArrayOf(3781, 15124, 21425),
                            intArrayOf(2587, 7761, 12072),
                            intArrayOf(106, 458, 810),
                            intArrayOf(22282, 29710, 31894),
                            intArrayOf(8508, 20926, 25984),
                            intArrayOf(3726, 12713, 18083),
                            intArrayOf(1620, 7112, 10893),
                            intArrayOf(729, 2236, 3495),
                            intArrayOf(30163, 32474, 32684),
                            intArrayOf(18304, 30464, 32000),
                            intArrayOf(11443, 26526, 29647),
                            intArrayOf(6007, 15292, 21299),
                            intArrayOf(2234, 6703, 8937),
                            intArrayOf(30954, 32177, 32571),
                            intArrayOf(17363, 29562, 31076),
                            intArrayOf(9686, 22464, 27410),
                            intArrayOf(8192, 16384, 21390),
                            intArrayOf(1755, 8046, 11264),
                            intArrayOf(31168, 32734, 32748),
                            intArrayOf(22486, 31441, 32471),
                            intArrayOf(12833, 25627, 29738),
                            intArrayOf(6980, 17379, 23122),
                            intArrayOf(3111, 8887, 13479),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576)
                        )
                    )
                ),
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(6041, 11854, 15927),
                            intArrayOf(20326, 30905, 32251),
                            intArrayOf(14164, 26831, 30725),
                            intArrayOf(9760, 20647, 26585),
                            intArrayOf(6416, 14953, 21219),
                            intArrayOf(2966, 7151, 10891),
                            intArrayOf(23567, 31374, 32254),
                            intArrayOf(14978, 27416, 30946),
                            intArrayOf(9434, 20225, 26254),
                            intArrayOf(6658, 14558, 20535),
                            intArrayOf(3916, 8677, 12989),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(18088, 29545, 31587),
                            intArrayOf(13062, 25843, 30073),
                            intArrayOf(8940, 16827, 22251),
                            intArrayOf(7654, 13220, 17973),
                            intArrayOf(5733, 10316, 14456),
                            intArrayOf(22879, 31388, 32114),
                            intArrayOf(15215, 27993, 30955),
                            intArrayOf(9397, 19445, 24978),
                            intArrayOf(3442, 9813, 15344),
                            intArrayOf(1368, 3936, 6532),
                            intArrayOf(25494, 32033, 32406),
                            intArrayOf(16772, 27963, 30718),
                            intArrayOf(9419, 18165, 23260),
                            intArrayOf(2677, 7501, 11797),
                            intArrayOf(1516, 4344, 7170),
                            intArrayOf(26556, 31454, 32101),
                            intArrayOf(17128, 27035, 30108),
                            intArrayOf(8324, 15344, 20249),
                            intArrayOf(1903, 5696, 9469),
                            intArrayOf(8192, 16384, 24576)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(8455, 19003, 24368),
                            intArrayOf(23563, 32021, 32604),
                            intArrayOf(16237, 29446, 31935),
                            intArrayOf(10724, 23999, 29358),
                            intArrayOf(6725, 17528, 24416),
                            intArrayOf(3927, 10927, 16825),
                            intArrayOf(26313, 32288, 32634),
                            intArrayOf(17430, 30095, 32095),
                            intArrayOf(11116, 24606, 29679),
                            intArrayOf(7195, 18384, 25269),
                            intArrayOf(4726, 12852, 19315),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(22822, 31648, 32483),
                            intArrayOf(16724, 29633, 31929),
                            intArrayOf(10261, 23033, 28725),
                            intArrayOf(7029, 17840, 24528),
                            intArrayOf(4867, 13886, 21502),
                            intArrayOf(25298, 31892, 32491),
                            intArrayOf(17809, 29330, 31512),
                            intArrayOf(9668, 21329, 26579),
                            intArrayOf(4774, 12956, 18976),
                            intArrayOf(2322, 7030, 11540),
                            intArrayOf(25472, 31920, 32543),
                            intArrayOf(17957, 29387, 31632),
                            intArrayOf(9196, 20593, 26400),
                            intArrayOf(4680, 12705, 19202),
                            intArrayOf(2917, 8456, 13436),
                            intArrayOf(26471, 32059, 32574),
                            intArrayOf(18458, 29783, 31909),
                            intArrayOf(8400, 19464, 25956),
                            intArrayOf(3812, 10973, 17206),
                            intArrayOf(8192, 16384, 24576)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(6779, 13743, 17678),
                            intArrayOf(24806, 31797, 32457),
                            intArrayOf(17616, 29047, 31372),
                            intArrayOf(11063, 23175, 28003),
                            intArrayOf(6521, 16110, 22324),
                            intArrayOf(2764, 7504, 11654),
                            intArrayOf(25266, 32367, 32637),
                            intArrayOf(19054, 30553, 32175),
                            intArrayOf(12139, 25212, 29807),
                            intArrayOf(7311, 18162, 24704),
                            intArrayOf(3397, 9164, 14074),
                            intArrayOf(25988, 32208, 32522),
                            intArrayOf(16253, 28912, 31526),
                            intArrayOf(9151, 21387, 27372),
                            intArrayOf(5688, 14915, 21496),
                            intArrayOf(2717, 7627, 12004),
                            intArrayOf(23144, 31855, 32443),
                            intArrayOf(16070, 28491, 31325),
                            intArrayOf(8702, 20467, 26517),
                            intArrayOf(5243, 13956, 20367),
                            intArrayOf(2621, 7335, 11567),
                            intArrayOf(26636, 32340, 32630),
                            intArrayOf(19990, 31050, 32341),
                            intArrayOf(13243, 26105, 30315),
                            intArrayOf(8588, 19521, 25918),
                            intArrayOf(4717, 11585, 17304),
                            intArrayOf(25844, 32292, 32582),
                            intArrayOf(19090, 30635, 32097),
                            intArrayOf(11963, 24546, 28939),
                            intArrayOf(6218, 16087, 22354),
                            intArrayOf(2340, 6608, 10426),
                            intArrayOf(28046, 32576, 32694),
                            intArrayOf(21178, 31313, 32296),
                            intArrayOf(13486, 26184, 29870),
                            intArrayOf(7149, 17871, 23723),
                            intArrayOf(2833, 7958, 12259),
                            intArrayOf(27710, 32528, 32686),
                            intArrayOf(20674, 31076, 32268),
                            intArrayOf(12413, 24955, 29243),
                            intArrayOf(6676, 16927, 23097),
                            intArrayOf(2966, 8333, 12919)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(8639, 19339, 24429),
                            intArrayOf(24404, 31837, 32525),
                            intArrayOf(16997, 29425, 31784),
                            intArrayOf(11253, 24234, 29149),
                            intArrayOf(6751, 17394, 24028),
                            intArrayOf(3490, 9830, 15191),
                            intArrayOf(26283, 32471, 32714),
                            intArrayOf(19599, 31168, 32442),
                            intArrayOf(13146, 26954, 30893),
                            intArrayOf(8214, 20588, 26890),
                            intArrayOf(4699, 13081, 19300),
                            intArrayOf(28212, 32458, 32669),
                            intArrayOf(18594, 30316, 32100),
                            intArrayOf(11219, 24408, 29234),
                            intArrayOf(6865, 17656, 24149),
                            intArrayOf(3678, 10362, 16006),
                            intArrayOf(25825, 32136, 32616),
                            intArrayOf(17313, 29853, 32021),
                            intArrayOf(11197, 24471, 29472),
                            intArrayOf(6947, 17781, 24405),
                            intArrayOf(3768, 10660, 16261),
                            intArrayOf(27352, 32500, 32706),
                            intArrayOf(20850, 31468, 32469),
                            intArrayOf(14021, 27707, 31133),
                            intArrayOf(8964, 21748, 27838),
                            intArrayOf(5437, 14665, 21187),
                            intArrayOf(26304, 32492, 32698),
                            intArrayOf(20409, 31380, 32385),
                            intArrayOf(13682, 27222, 30632),
                            intArrayOf(8974, 21236, 26685),
                            intArrayOf(4234, 11665, 16934),
                            intArrayOf(26273, 32357, 32711),
                            intArrayOf(20672, 31242, 32441),
                            intArrayOf(14172, 27254, 30902),
                            intArrayOf(9870, 21898, 27275),
                            intArrayOf(5164, 13506, 19270),
                            intArrayOf(26725, 32459, 32728),
                            intArrayOf(20991, 31442, 32527),
                            intArrayOf(13071, 26434, 30811),
                            intArrayOf(8184, 20090, 26742),
                            intArrayOf(4803, 13255, 19895)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(7555, 14942, 18501),
                            intArrayOf(24410, 31178, 32287),
                            intArrayOf(14394, 26738, 30253),
                            intArrayOf(8413, 19554, 25195),
                            intArrayOf(4766, 12924, 18785),
                            intArrayOf(2029, 5806, 9207),
                            intArrayOf(26776, 32364, 32663),
                            intArrayOf(18732, 29967, 31931),
                            intArrayOf(11005, 23786, 28852),
                            intArrayOf(6466, 16909, 23510),
                            intArrayOf(3044, 8638, 13419),
                            intArrayOf(29208, 32582, 32704),
                            intArrayOf(20068, 30857, 32208),
                            intArrayOf(12003, 25085, 29595),
                            intArrayOf(6947, 17750, 24189),
                            intArrayOf(3245, 9103, 14007),
                            intArrayOf(27359, 32465, 32669),
                            intArrayOf(19421, 30614, 32174),
                            intArrayOf(11915, 25010, 29579),
                            intArrayOf(6950, 17676, 24074),
                            intArrayOf(3007, 8473, 13096),
                            intArrayOf(29002, 32676, 32735),
                            intArrayOf(22102, 31849, 32576),
                            intArrayOf(14408, 28009, 31405),
                            intArrayOf(9027, 21679, 27931),
                            intArrayOf(4694, 12678, 18748),
                            intArrayOf(28216, 32528, 32682),
                            intArrayOf(20849, 31264, 32318),
                            intArrayOf(12756, 25815, 29751),
                            intArrayOf(7565, 18801, 24923),
                            intArrayOf(3509, 9533, 14477),
                            intArrayOf(30133, 32687, 32739),
                            intArrayOf(23063, 31910, 32515),
                            intArrayOf(14588, 28051, 31132),
                            intArrayOf(9085, 21649, 27457),
                            intArrayOf(4261, 11654, 17264),
                            intArrayOf(29518, 32691, 32748),
                            intArrayOf(22451, 31959, 32613),
                            intArrayOf(14864, 28722, 31700),
                            intArrayOf(9695, 22964, 28716),
                            intArrayOf(4932, 13358, 19502)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(6465, 16958, 21688),
                            intArrayOf(25199, 31514, 32360),
                            intArrayOf(14774, 27149, 30607),
                            intArrayOf(9257, 21438, 26972),
                            intArrayOf(5723, 15183, 21882),
                            intArrayOf(3150, 8879, 13731),
                            intArrayOf(26989, 32262, 32682),
                            intArrayOf(17396, 29937, 32085),
                            intArrayOf(11387, 24901, 29784),
                            intArrayOf(7289, 18821, 25548),
                            intArrayOf(3734, 10577, 16086),
                            intArrayOf(29728, 32501, 32695),
                            intArrayOf(17431, 29701, 31903),
                            intArrayOf(9921, 22826, 28300),
                            intArrayOf(5896, 15434, 22068),
                            intArrayOf(3430, 9646, 14757),
                            intArrayOf(28614, 32511, 32705),
                            intArrayOf(19364, 30638, 32263),
                            intArrayOf(13129, 26254, 30402),
                            intArrayOf(8754, 20484, 26440),
                            intArrayOf(4378, 11607, 17110),
                            intArrayOf(30292, 32671, 32744),
                            intArrayOf(21780, 31603, 32501),
                            intArrayOf(14314, 27829, 31291),
                            intArrayOf(9611, 22327, 28263),
                            intArrayOf(4890, 13087, 19065),
                            intArrayOf(25862, 32567, 32733),
                            intArrayOf(20794, 32050, 32567),
                            intArrayOf(17243, 30625, 32254),
                            intArrayOf(13283, 27628, 31474),
                            intArrayOf(9669, 22532, 28918),
                            intArrayOf(27435, 32697, 32748),
                            intArrayOf(24922, 32390, 32714),
                            intArrayOf(21449, 31504, 32536),
                            intArrayOf(16392, 29729, 31832),
                            intArrayOf(11692, 24884, 29076),
                            intArrayOf(24193, 32290, 32735),
                            intArrayOf(18909, 31104, 32563),
                            intArrayOf(12236, 26841, 31403),
                            intArrayOf(8171, 21840, 29082),
                            intArrayOf(7224, 17280, 25275)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(3078, 6839, 9890),
                            intArrayOf(13837, 20450, 24479),
                            intArrayOf(5914, 14222, 19328),
                            intArrayOf(3866, 10267, 14762),
                            intArrayOf(2612, 7208, 11042),
                            intArrayOf(1067, 2991, 4776),
                            intArrayOf(25817, 31646, 32529),
                            intArrayOf(13708, 26338, 30385),
                            intArrayOf(7328, 18585, 24870),
                            intArrayOf(4691, 13080, 19276),
                            intArrayOf(1825, 5253, 8352),
                            intArrayOf(29386, 32315, 32624),
                            intArrayOf(17160, 29001, 31360),
                            intArrayOf(9602, 21862, 27396),
                            intArrayOf(5915, 15772, 22148),
                            intArrayOf(2786, 7779, 12047),
                            intArrayOf(29246, 32450, 32663),
                            intArrayOf(18696, 29929, 31818),
                            intArrayOf(10510, 23369, 28560),
                            intArrayOf(6229, 16499, 23125),
                            intArrayOf(2608, 7448, 11705),
                            intArrayOf(30753, 32710, 32748),
                            intArrayOf(21638, 31487, 32503),
                            intArrayOf(12937, 26854, 30870),
                            intArrayOf(8182, 20596, 26970),
                            intArrayOf(3637, 10269, 15497),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(5244, 12150, 16906),
                            intArrayOf(20486, 26858, 29701),
                            intArrayOf(7756, 18317, 23735),
                            intArrayOf(3452, 9256, 13146),
                            intArrayOf(2020, 5206, 8229),
                            intArrayOf(1801, 4993, 7903),
                            intArrayOf(27051, 31858, 32531),
                            intArrayOf(15988, 27531, 30619),
                            intArrayOf(9188, 21484, 26719),
                            intArrayOf(6273, 17186, 23800),
                            intArrayOf(3108, 9355, 14764),
                            intArrayOf(31076, 32520, 32680),
                            intArrayOf(18119, 30037, 31850),
                            intArrayOf(10244, 22969, 27472),
                            intArrayOf(4692, 14077, 19273),
                            intArrayOf(3694, 11677, 17556),
                            intArrayOf(30060, 32581, 32720),
                            intArrayOf(21011, 30775, 32120),
                            intArrayOf(11931, 24820, 29289),
                            intArrayOf(7119, 17662, 24356),
                            intArrayOf(3833, 10706, 16304),
                            intArrayOf(31954, 32731, 32748),
                            intArrayOf(23913, 31724, 32489),
                            intArrayOf(15520, 28060, 31286),
                            intArrayOf(11517, 23008, 28571),
                            intArrayOf(6193, 14508, 20629),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(1035, 2807, 4156),
                            intArrayOf(13162, 18138, 20939),
                            intArrayOf(2696, 6633, 8755),
                            intArrayOf(1373, 4161, 6853),
                            intArrayOf(1099, 2746, 4716),
                            intArrayOf(340, 1021, 1599),
                            intArrayOf(22826, 30419, 32135),
                            intArrayOf(10395, 21762, 26942),
                            intArrayOf(4726, 12407, 17361),
                            intArrayOf(2447, 7080, 10593),
                            intArrayOf(1227, 3717, 6011),
                            intArrayOf(28156, 31424, 31934),
                            intArrayOf(16915, 27754, 30373),
                            intArrayOf(9148, 20990, 26431),
                            intArrayOf(5950, 15515, 21148),
                            intArrayOf(2492, 7327, 11526),
                            intArrayOf(30602, 32477, 32670),
                            intArrayOf(20026, 29955, 31568),
                            intArrayOf(11220, 23628, 28105),
                            intArrayOf(6652, 17019, 22973),
                            intArrayOf(3064, 8536, 13043),
                            intArrayOf(31769, 32724, 32748),
                            intArrayOf(22230, 30887, 32373),
                            intArrayOf(12234, 25079, 29731),
                            intArrayOf(7326, 18816, 25353),
                            intArrayOf(3933, 10907, 16616),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576)
                        )
                    )
                ),
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(8896, 16227, 20630),
                            intArrayOf(23629, 31782, 32527),
                            intArrayOf(15173, 27755, 31321),
                            intArrayOf(10158, 21233, 27382),
                            intArrayOf(6420, 14857, 21558),
                            intArrayOf(3269, 8155, 12646),
                            intArrayOf(24835, 32009, 32496),
                            intArrayOf(16509, 28421, 31579),
                            intArrayOf(10957, 21514, 27418),
                            intArrayOf(7881, 15930, 22096),
                            intArrayOf(5388, 10960, 15918),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(20745, 30773, 32093),
                            intArrayOf(15200, 27221, 30861),
                            intArrayOf(13032, 20873, 25667),
                            intArrayOf(12285, 18663, 23494),
                            intArrayOf(11563, 17481, 21489),
                            intArrayOf(26260, 31982, 32320),
                            intArrayOf(15397, 28083, 31100),
                            intArrayOf(9742, 19217, 24824),
                            intArrayOf(3261, 9629, 15362),
                            intArrayOf(1480, 4322, 7499),
                            intArrayOf(27599, 32256, 32460),
                            intArrayOf(16857, 27659, 30774),
                            intArrayOf(9551, 18290, 23748),
                            intArrayOf(3052, 8933, 14103),
                            intArrayOf(2021, 5910, 9787),
                            intArrayOf(29005, 32015, 32392),
                            intArrayOf(17677, 27694, 30863),
                            intArrayOf(9204, 17356, 23219),
                            intArrayOf(2403, 7516, 12814),
                            intArrayOf(8192, 16384, 24576)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(10808, 22056, 26896),
                            intArrayOf(25739, 32313, 32676),
                            intArrayOf(17288, 30203, 32221),
                            intArrayOf(11359, 24878, 29896),
                            intArrayOf(6949, 17767, 24893),
                            intArrayOf(4287, 11796, 18071),
                            intArrayOf(27880, 32521, 32705),
                            intArrayOf(19038, 31004, 32414),
                            intArrayOf(12564, 26345, 30768),
                            intArrayOf(8269, 19947, 26779),
                            intArrayOf(5674, 14657, 21674),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(25742, 32319, 32671),
                            intArrayOf(19557, 31164, 32454),
                            intArrayOf(13381, 26381, 30755),
                            intArrayOf(10101, 21466, 26722),
                            intArrayOf(9209, 19650, 26825),
                            intArrayOf(27107, 31917, 32432),
                            intArrayOf(18056, 28893, 31203),
                            intArrayOf(10200, 21434, 26764),
                            intArrayOf(4660, 12913, 19502),
                            intArrayOf(2368, 6930, 12504),
                            intArrayOf(26960, 32158, 32613),
                            intArrayOf(18628, 30005, 32031),
                            intArrayOf(10233, 22442, 28232),
                            intArrayOf(5471, 14630, 21516),
                            intArrayOf(3235, 10767, 17109),
                            intArrayOf(27696, 32440, 32692),
                            intArrayOf(20032, 31167, 32438),
                            intArrayOf(8700, 21341, 28442),
                            intArrayOf(5662, 14831, 21795),
                            intArrayOf(8192, 16384, 24576)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(9704, 17294, 21132),
                            intArrayOf(26762, 32278, 32633),
                            intArrayOf(18382, 29620, 31819),
                            intArrayOf(10891, 23475, 28723),
                            intArrayOf(6358, 16583, 23309),
                            intArrayOf(3248, 9118, 14141),
                            intArrayOf(27204, 32573, 32699),
                            intArrayOf(19818, 30824, 32329),
                            intArrayOf(11772, 25120, 30041),
                            intArrayOf(6995, 18033, 25039),
                            intArrayOf(3752, 10442, 16098),
                            intArrayOf(27222, 32256, 32559),
                            intArrayOf(15356, 28399, 31475),
                            intArrayOf(8821, 20635, 27057),
                            intArrayOf(5511, 14404, 21239),
                            intArrayOf(2935, 8222, 13051),
                            intArrayOf(24875, 32120, 32529),
                            intArrayOf(15233, 28265, 31445),
                            intArrayOf(8605, 20570, 26932),
                            intArrayOf(5431, 14413, 21196),
                            intArrayOf(2994, 8341, 13223),
                            intArrayOf(28201, 32604, 32700),
                            intArrayOf(21041, 31446, 32456),
                            intArrayOf(13221, 26213, 30475),
                            intArrayOf(8255, 19385, 26037),
                            intArrayOf(4930, 12585, 18830),
                            intArrayOf(28768, 32448, 32627),
                            intArrayOf(19705, 30561, 32021),
                            intArrayOf(11572, 23589, 28220),
                            intArrayOf(5532, 15034, 21446),
                            intArrayOf(2460, 7150, 11456),
                            intArrayOf(29874, 32619, 32699),
                            intArrayOf(21621, 31071, 32201),
                            intArrayOf(12511, 24747, 28992),
                            intArrayOf(6281, 16395, 22748),
                            intArrayOf(3246, 9278, 14497),
                            intArrayOf(29715, 32625, 32712),
                            intArrayOf(20958, 31011, 32283),
                            intArrayOf(11233, 23671, 28806),
                            intArrayOf(6012, 16128, 22868),
                            intArrayOf(3427, 9851, 15414)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(11016, 22111, 26794),
                            intArrayOf(25946, 32357, 32677),
                            intArrayOf(17890, 30452, 32252),
                            intArrayOf(11678, 25142, 29816),
                            intArrayOf(6720, 17534, 24584),
                            intArrayOf(4230, 11665, 17820),
                            intArrayOf(28400, 32623, 32747),
                            intArrayOf(21164, 31668, 32575),
                            intArrayOf(13572, 27388, 31182),
                            intArrayOf(8234, 20750, 27358),
                            intArrayOf(5065, 14055, 20897),
                            intArrayOf(28981, 32547, 32705),
                            intArrayOf(18681, 30543, 32239),
                            intArrayOf(10919, 24075, 29286),
                            intArrayOf(6431, 17199, 24077),
                            intArrayOf(3819, 10464, 16618),
                            intArrayOf(26870, 32467, 32693),
                            intArrayOf(19041, 30831, 32347),
                            intArrayOf(11794, 25211, 30016),
                            intArrayOf(6888, 18019, 24970),
                            intArrayOf(4370, 12363, 18992),
                            intArrayOf(29578, 32670, 32744),
                            intArrayOf(23159, 32007, 32613),
                            intArrayOf(15315, 28669, 31676),
                            intArrayOf(9298, 22607, 28782),
                            intArrayOf(6144, 15913, 22968),
                            intArrayOf(28110, 32499, 32669),
                            intArrayOf(21574, 30937, 32015),
                            intArrayOf(12759, 24818, 28727),
                            intArrayOf(6545, 16761, 23042),
                            intArrayOf(3649, 10597, 16833),
                            intArrayOf(28163, 32552, 32728),
                            intArrayOf(22101, 31469, 32464),
                            intArrayOf(13160, 25472, 30143),
                            intArrayOf(7303, 18684, 25468),
                            intArrayOf(5241, 13975, 20955),
                            intArrayOf(28400, 32631, 32744),
                            intArrayOf(22104, 31793, 32603),
                            intArrayOf(13557, 26571, 30846),
                            intArrayOf(7749, 19861, 26675),
                            intArrayOf(4873, 14030, 21234)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(9800, 17635, 21073),
                            intArrayOf(26153, 31885, 32527),
                            intArrayOf(15038, 27852, 31006),
                            intArrayOf(8718, 20564, 26486),
                            intArrayOf(5128, 14076, 20514),
                            intArrayOf(2636, 7566, 11925),
                            intArrayOf(27551, 32504, 32701),
                            intArrayOf(18310, 30054, 32100),
                            intArrayOf(10211, 23420, 29082),
                            intArrayOf(6222, 16876, 23916),
                            intArrayOf(3462, 9954, 15498),
                            intArrayOf(29991, 32633, 32721),
                            intArrayOf(19883, 30751, 32201),
                            intArrayOf(11141, 24184, 29285),
                            intArrayOf(6420, 16940, 23774),
                            intArrayOf(3392, 9753, 15118),
                            intArrayOf(28465, 32616, 32712),
                            intArrayOf(19850, 30702, 32244),
                            intArrayOf(10983, 24024, 29223),
                            intArrayOf(6294, 16770, 23582),
                            intArrayOf(3244, 9283, 14509),
                            intArrayOf(30023, 32717, 32748),
                            intArrayOf(22940, 32032, 32626),
                            intArrayOf(14282, 27928, 31473),
                            intArrayOf(8562, 21327, 27914),
                            intArrayOf(4846, 13393, 19919),
                            intArrayOf(29981, 32590, 32695),
                            intArrayOf(20465, 30963, 32166),
                            intArrayOf(11479, 23579, 28195),
                            intArrayOf(5916, 15648, 22073),
                            intArrayOf(3031, 8605, 13398),
                            intArrayOf(31146, 32691, 32739),
                            intArrayOf(23106, 31724, 32444),
                            intArrayOf(13783, 26738, 30439),
                            intArrayOf(7852, 19468, 25807),
                            intArrayOf(3860, 11124, 16853),
                            intArrayOf(31014, 32724, 32748),
                            intArrayOf(23629, 32109, 32628),
                            intArrayOf(14747, 28115, 31403),
                            intArrayOf(8545, 21242, 27478),
                            intArrayOf(4574, 12781, 19067)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(9185, 19694, 24688),
                            intArrayOf(26081, 31985, 32621),
                            intArrayOf(16015, 29000, 31787),
                            intArrayOf(10542, 23690, 29206),
                            intArrayOf(6732, 17945, 24677),
                            intArrayOf(3916, 11039, 16722),
                            intArrayOf(28224, 32566, 32744),
                            intArrayOf(19100, 31138, 32485),
                            intArrayOf(12528, 26620, 30879),
                            intArrayOf(7741, 20277, 26885),
                            intArrayOf(4566, 12845, 18990),
                            intArrayOf(29933, 32593, 32718),
                            intArrayOf(17670, 30333, 32155),
                            intArrayOf(10385, 23600, 28909),
                            intArrayOf(6243, 16236, 22407),
                            intArrayOf(3976, 10389, 16017),
                            intArrayOf(28377, 32561, 32738),
                            intArrayOf(19366, 31175, 32482),
                            intArrayOf(13327, 27175, 31094),
                            intArrayOf(8258, 20769, 27143),
                            intArrayOf(4703, 13198, 19527),
                            intArrayOf(31086, 32706, 32748),
                            intArrayOf(22853, 31902, 32583),
                            intArrayOf(14759, 28186, 31419),
                            intArrayOf(9284, 22382, 28348),
                            intArrayOf(5585, 15192, 21868),
                            intArrayOf(28291, 32652, 32746),
                            intArrayOf(19849, 32107, 32571),
                            intArrayOf(14834, 26818, 29214),
                            intArrayOf(10306, 22594, 28672),
                            intArrayOf(6615, 17384, 23384),
                            intArrayOf(28947, 32604, 32745),
                            intArrayOf(25625, 32289, 32646),
                            intArrayOf(18758, 28672, 31403),
                            intArrayOf(10017, 23430, 28523),
                            intArrayOf(6862, 15269, 22131),
                            intArrayOf(23933, 32509, 32739),
                            intArrayOf(19927, 31495, 32631),
                            intArrayOf(11903, 26023, 30621),
                            intArrayOf(7026, 20094, 27252),
                            intArrayOf(5998, 18106, 24437)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(4456, 11274, 15533),
                            intArrayOf(21219, 29079, 31616),
                            intArrayOf(11173, 23774, 28567),
                            intArrayOf(7282, 18293, 24263),
                            intArrayOf(4890, 13286, 19115),
                            intArrayOf(1890, 5508, 8659),
                            intArrayOf(26651, 32136, 32647),
                            intArrayOf(14630, 28254, 31455),
                            intArrayOf(8716, 21287, 27395),
                            intArrayOf(5615, 15331, 22008),
                            intArrayOf(2675, 7700, 12150),
                            intArrayOf(29954, 32526, 32690),
                            intArrayOf(16126, 28982, 31633),
                            intArrayOf(9030, 21361, 27352),
                            intArrayOf(5411, 14793, 21271),
                            intArrayOf(2943, 8422, 13163),
                            intArrayOf(29539, 32601, 32730),
                            intArrayOf(18125, 30385, 32201),
                            intArrayOf(10422, 24090, 29468),
                            intArrayOf(6468, 17487, 24438),
                            intArrayOf(2970, 8653, 13531),
                            intArrayOf(30912, 32715, 32748),
                            intArrayOf(20666, 31373, 32497),
                            intArrayOf(12509, 26640, 30917),
                            intArrayOf(8058, 20629, 27290),
                            intArrayOf(4231, 12006, 18052),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(10202, 20633, 25484),
                            intArrayOf(27336, 31445, 32352),
                            intArrayOf(12420, 24384, 28552),
                            intArrayOf(7648, 18115, 23856),
                            intArrayOf(5662, 14341, 19902),
                            intArrayOf(3611, 10328, 15390),
                            intArrayOf(30945, 32616, 32736),
                            intArrayOf(18682, 30505, 32253),
                            intArrayOf(11513, 25336, 30203),
                            intArrayOf(7449, 19452, 26148),
                            intArrayOf(4482, 13051, 18886),
                            intArrayOf(32022, 32690, 32747),
                            intArrayOf(18578, 30501, 32146),
                            intArrayOf(11249, 23368, 28631),
                            intArrayOf(5645, 16958, 22158),
                            intArrayOf(5009, 11444, 16637),
                            intArrayOf(31357, 32710, 32748),
                            intArrayOf(21552, 31494, 32504),
                            intArrayOf(13891, 27677, 31340),
                            intArrayOf(9051, 22098, 28172),
                            intArrayOf(5190, 13377, 19486),
                            intArrayOf(32364, 32740, 32748),
                            intArrayOf(24839, 31907, 32551),
                            intArrayOf(17160, 28779, 31696),
                            intArrayOf(12452, 24137, 29602),
                            intArrayOf(6165, 15389, 22477),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(2575, 7281, 11077),
                            intArrayOf(14002, 20866, 25402),
                            intArrayOf(6343, 15056, 19658),
                            intArrayOf(4474, 11858, 17041),
                            intArrayOf(2865, 8299, 12534),
                            intArrayOf(1344, 3949, 6391),
                            intArrayOf(24720, 31239, 32459),
                            intArrayOf(12585, 25356, 29968),
                            intArrayOf(7181, 18246, 24444),
                            intArrayOf(5025, 13667, 19885),
                            intArrayOf(2521, 7304, 11605),
                            intArrayOf(29908, 32252, 32584),
                            intArrayOf(17421, 29156, 31575),
                            intArrayOf(9889, 22188, 27782),
                            intArrayOf(5878, 15647, 22123),
                            intArrayOf(2814, 8665, 13323),
                            intArrayOf(30183, 32568, 32713),
                            intArrayOf(18528, 30195, 32049),
                            intArrayOf(10982, 24606, 29657),
                            intArrayOf(6957, 18165, 25231),
                            intArrayOf(3508, 10118, 15468),
                            intArrayOf(31761, 32736, 32748),
                            intArrayOf(21041, 31328, 32546),
                            intArrayOf(12568, 26732, 31166),
                            intArrayOf(8052, 20720, 27733),
                            intArrayOf(4336, 12192, 18396),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576)
                        )
                    )
                ),
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(7062, 16472, 22319),
                            intArrayOf(24538, 32261, 32674),
                            intArrayOf(13675, 28041, 31779),
                            intArrayOf(8590, 20674, 27631),
                            intArrayOf(5685, 14675, 22013),
                            intArrayOf(3655, 9898, 15731),
                            intArrayOf(26493, 32418, 32658),
                            intArrayOf(16376, 29342, 32090),
                            intArrayOf(10594, 22649, 28970),
                            intArrayOf(8176, 17170, 24303),
                            intArrayOf(5605, 12694, 19139),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(23888, 31902, 32542),
                            intArrayOf(18612, 29687, 31987),
                            intArrayOf(16245, 24852, 29249),
                            intArrayOf(15765, 22608, 27559),
                            intArrayOf(19895, 24699, 27510),
                            intArrayOf(28401, 32212, 32457),
                            intArrayOf(15274, 27825, 30980),
                            intArrayOf(9364, 18128, 24332),
                            intArrayOf(2283, 8193, 15082),
                            intArrayOf(1228, 3972, 7881),
                            intArrayOf(29455, 32469, 32620),
                            intArrayOf(17981, 28245, 31388),
                            intArrayOf(10921, 20098, 26240),
                            intArrayOf(3743, 11829, 18657),
                            intArrayOf(2374, 9593, 15715),
                            intArrayOf(31068, 32466, 32635),
                            intArrayOf(20321, 29572, 31971),
                            intArrayOf(10771, 20255, 27119),
                            intArrayOf(2795, 10410, 17361),
                            intArrayOf(8192, 16384, 24576)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(9320, 22102, 27840),
                            intArrayOf(27057, 32464, 32724),
                            intArrayOf(16331, 30268, 32309),
                            intArrayOf(10319, 23935, 29720),
                            intArrayOf(6189, 16448, 24106),
                            intArrayOf(3589, 10884, 18808),
                            intArrayOf(29026, 32624, 32748),
                            intArrayOf(19226, 31507, 32587),
                            intArrayOf(12692, 26921, 31203),
                            intArrayOf(7049, 19532, 27635),
                            intArrayOf(7727, 15669, 23252),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(28056, 32625, 32748),
                            intArrayOf(22383, 32075, 32669),
                            intArrayOf(15417, 27098, 31749),
                            intArrayOf(18127, 26493, 27190),
                            intArrayOf(5461, 16384, 21845),
                            intArrayOf(27982, 32091, 32584),
                            intArrayOf(19045, 29868, 31972),
                            intArrayOf(10397, 22266, 27932),
                            intArrayOf(5990, 13697, 21500),
                            intArrayOf(1792, 6912, 15104),
                            intArrayOf(28198, 32501, 32718),
                            intArrayOf(21534, 31521, 32569),
                            intArrayOf(11109, 25217, 30017),
                            intArrayOf(5671, 15124, 26151),
                            intArrayOf(4681, 14043, 18725),
                            intArrayOf(28688, 32580, 32741),
                            intArrayOf(22576, 32079, 32661),
                            intArrayOf(10627, 22141, 28340),
                            intArrayOf(9362, 14043, 28087),
                            intArrayOf(8192, 16384, 24576)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(7754, 16948, 22142),
                            intArrayOf(25670, 32330, 32691),
                            intArrayOf(15663, 29225, 31994),
                            intArrayOf(9878, 23288, 29158),
                            intArrayOf(6419, 17088, 24336),
                            intArrayOf(3859, 11003, 17039),
                            intArrayOf(27562, 32595, 32725),
                            intArrayOf(17575, 30588, 32399),
                            intArrayOf(10819, 24838, 30309),
                            intArrayOf(7124, 18686, 25916),
                            intArrayOf(4479, 12688, 19340),
                            intArrayOf(28385, 32476, 32673),
                            intArrayOf(15306, 29005, 31938),
                            intArrayOf(8937, 21615, 28322),
                            intArrayOf(5982, 15603, 22786),
                            intArrayOf(3620, 10267, 16136),
                            intArrayOf(27280, 32464, 32667),
                            intArrayOf(15607, 29160, 32004),
                            intArrayOf(9091, 22135, 28740),
                            intArrayOf(6232, 16632, 24020),
                            intArrayOf(4047, 11377, 17672),
                            intArrayOf(29220, 32630, 32718),
                            intArrayOf(19650, 31220, 32462),
                            intArrayOf(13050, 26312, 30827),
                            intArrayOf(9228, 20870, 27468),
                            intArrayOf(6146, 15149, 21971),
                            intArrayOf(30169, 32481, 32623),
                            intArrayOf(17212, 29311, 31554),
                            intArrayOf(9911, 21311, 26882),
                            intArrayOf(4487, 13314, 20372),
                            intArrayOf(2570, 7772, 12889),
                            intArrayOf(30924, 32613, 32708),
                            intArrayOf(19490, 30206, 32107),
                            intArrayOf(11232, 23998, 29276),
                            intArrayOf(6769, 17955, 25035),
                            intArrayOf(4398, 12623, 19214),
                            intArrayOf(30609, 32627, 32722),
                            intArrayOf(19370, 30582, 32287),
                            intArrayOf(10457, 23619, 29409),
                            intArrayOf(6443, 17637, 24834),
                            intArrayOf(4645, 13236, 20106)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(8626, 20271, 26216),
                            intArrayOf(26707, 32406, 32711),
                            intArrayOf(16999, 30329, 32286),
                            intArrayOf(11445, 25123, 30286),
                            intArrayOf(6411, 18828, 25601),
                            intArrayOf(6801, 12458, 20248),
                            intArrayOf(29918, 32682, 32748),
                            intArrayOf(20649, 31739, 32618),
                            intArrayOf(12879, 27773, 31581),
                            intArrayOf(7896, 21751, 28244),
                            intArrayOf(5260, 14870, 23698),
                            intArrayOf(29252, 32593, 32731),
                            intArrayOf(17072, 30460, 32294),
                            intArrayOf(10653, 24143, 29365),
                            intArrayOf(6536, 17490, 23983),
                            intArrayOf(4929, 13170, 20085),
                            intArrayOf(28137, 32518, 32715),
                            intArrayOf(18171, 30784, 32407),
                            intArrayOf(11437, 25436, 30459),
                            intArrayOf(7252, 18534, 26176),
                            intArrayOf(4126, 13353, 20978),
                            intArrayOf(31162, 32726, 32748),
                            intArrayOf(23017, 32222, 32701),
                            intArrayOf(15629, 29233, 32046),
                            intArrayOf(9387, 22621, 29480),
                            intArrayOf(6922, 17616, 25010),
                            intArrayOf(28838, 32265, 32614),
                            intArrayOf(19701, 30206, 31920),
                            intArrayOf(11214, 22410, 27933),
                            intArrayOf(5320, 14177, 23034),
                            intArrayOf(5049, 12881, 17827),
                            intArrayOf(27484, 32471, 32734),
                            intArrayOf(21076, 31526, 32561),
                            intArrayOf(12707, 26303, 31211),
                            intArrayOf(8169, 21722, 28219),
                            intArrayOf(6045, 19406, 27042),
                            intArrayOf(27753, 32572, 32745),
                            intArrayOf(20832, 31878, 32653),
                            intArrayOf(13250, 27356, 31674),
                            intArrayOf(7718, 21508, 29858),
                            intArrayOf(7209, 18350, 25559)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(7876, 16901, 21741),
                            intArrayOf(24001, 31898, 32625),
                            intArrayOf(14529, 27959, 31451),
                            intArrayOf(8273, 20818, 27258),
                            intArrayOf(5278, 14673, 21510),
                            intArrayOf(2983, 8843, 14039),
                            intArrayOf(28016, 32574, 32732),
                            intArrayOf(17471, 30306, 32301),
                            intArrayOf(10224, 24063, 29728),
                            intArrayOf(6602, 17954, 25052),
                            intArrayOf(4002, 11585, 17759),
                            intArrayOf(30190, 32634, 32739),
                            intArrayOf(17497, 30282, 32270),
                            intArrayOf(10229, 23729, 29538),
                            intArrayOf(6344, 17211, 24440),
                            intArrayOf(3849, 11189, 17108),
                            intArrayOf(28570, 32583, 32726),
                            intArrayOf(17521, 30161, 32238),
                            intArrayOf(10153, 23565, 29378),
                            intArrayOf(6455, 17341, 24443),
                            intArrayOf(3907, 11042, 17024),
                            intArrayOf(30689, 32715, 32748),
                            intArrayOf(21546, 31840, 32610),
                            intArrayOf(13547, 27581, 31459),
                            intArrayOf(8912, 21757, 28309),
                            intArrayOf(5548, 15080, 22046),
                            intArrayOf(30783, 32540, 32685),
                            intArrayOf(17540, 29528, 31668),
                            intArrayOf(10160, 21468, 26783),
                            intArrayOf(4724, 13393, 20054),
                            intArrayOf(2702, 8174, 13102),
                            intArrayOf(31648, 32686, 32742),
                            intArrayOf(20954, 31094, 32337),
                            intArrayOf(12420, 25698, 30179),
                            intArrayOf(7304, 19320, 26248),
                            intArrayOf(4366, 12261, 18864),
                            intArrayOf(31581, 32723, 32748),
                            intArrayOf(21373, 31586, 32525),
                            intArrayOf(12744, 26625, 30885),
                            intArrayOf(7431, 20322, 26950),
                            intArrayOf(4692, 13323, 20111)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(7833, 18369, 24095),
                            intArrayOf(26650, 32273, 32702),
                            intArrayOf(16371, 29961, 32191),
                            intArrayOf(11055, 24082, 29629),
                            intArrayOf(6892, 18644, 25400),
                            intArrayOf(5006, 13057, 19240),
                            intArrayOf(29834, 32666, 32748),
                            intArrayOf(19577, 31335, 32570),
                            intArrayOf(12253, 26509, 31122),
                            intArrayOf(7991, 20772, 27711),
                            intArrayOf(5677, 15910, 23059),
                            intArrayOf(30109, 32532, 32720),
                            intArrayOf(16747, 30166, 32252),
                            intArrayOf(10134, 23542, 29184),
                            intArrayOf(5791, 16176, 23556),
                            intArrayOf(4362, 10414, 17284),
                            intArrayOf(29492, 32626, 32748),
                            intArrayOf(19894, 31402, 32525),
                            intArrayOf(12942, 27071, 30869),
                            intArrayOf(8346, 21216, 27405),
                            intArrayOf(6572, 17087, 23859),
                            intArrayOf(32035, 32735, 32748),
                            intArrayOf(22957, 31838, 32618),
                            intArrayOf(14724, 28572, 31772),
                            intArrayOf(10364, 23999, 29553),
                            intArrayOf(7004, 18433, 25655),
                            intArrayOf(27528, 32277, 32681),
                            intArrayOf(16959, 31171, 32096),
                            intArrayOf(10486, 23593, 27962),
                            intArrayOf(8192, 16384, 23211),
                            intArrayOf(8937, 17873, 20852),
                            intArrayOf(27715, 32002, 32615),
                            intArrayOf(15073, 29491, 31676),
                            intArrayOf(11264, 24576, 28672),
                            intArrayOf(2341, 18725, 23406),
                            intArrayOf(7282, 18204, 25486),
                            intArrayOf(28547, 32213, 32657),
                            intArrayOf(20788, 29773, 32239),
                            intArrayOf(6780, 21469, 30508),
                            intArrayOf(5958, 14895, 23831),
                            intArrayOf(16384, 21845, 27307)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(5992, 14304, 19765),
                            intArrayOf(22612, 31238, 32456),
                            intArrayOf(13456, 27162, 31087),
                            intArrayOf(8001, 20062, 26504),
                            intArrayOf(5168, 14105, 20764),
                            intArrayOf(2632, 7771, 12385),
                            intArrayOf(27034, 32344, 32709),
                            intArrayOf(15850, 29415, 31997),
                            intArrayOf(9494, 22776, 28841),
                            intArrayOf(6151, 16830, 23969),
                            intArrayOf(3461, 10039, 15722),
                            intArrayOf(30134, 32569, 32731),
                            intArrayOf(15638, 29422, 31945),
                            intArrayOf(9150, 21865, 28218),
                            intArrayOf(5647, 15719, 22676),
                            intArrayOf(3402, 9772, 15477),
                            intArrayOf(28530, 32586, 32735),
                            intArrayOf(17139, 30298, 32292),
                            intArrayOf(10200, 24039, 29685),
                            intArrayOf(6419, 17674, 24786),
                            intArrayOf(3544, 10225, 15824),
                            intArrayOf(31333, 32726, 32748),
                            intArrayOf(20618, 31487, 32544),
                            intArrayOf(12901, 27217, 31232),
                            intArrayOf(8624, 21734, 28171),
                            intArrayOf(5104, 14191, 20748),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(11206, 21090, 26561),
                            intArrayOf(28759, 32279, 32671),
                            intArrayOf(14171, 27952, 31569),
                            intArrayOf(9743, 22907, 29141),
                            intArrayOf(6871, 17886, 24868),
                            intArrayOf(4960, 13152, 19315),
                            intArrayOf(31077, 32661, 32748),
                            intArrayOf(19400, 31195, 32515),
                            intArrayOf(12752, 26858, 31040),
                            intArrayOf(8370, 22098, 28591),
                            intArrayOf(5457, 15373, 22298),
                            intArrayOf(31697, 32706, 32748),
                            intArrayOf(17860, 30657, 32333),
                            intArrayOf(12510, 24812, 29261),
                            intArrayOf(6180, 19124, 24722),
                            intArrayOf(5041, 13548, 17959),
                            intArrayOf(31552, 32716, 32748),
                            intArrayOf(21908, 31769, 32623),
                            intArrayOf(14470, 28201, 31565),
                            intArrayOf(9493, 22982, 28608),
                            intArrayOf(6858, 17240, 24137),
                            intArrayOf(32543, 32752, 32756),
                            intArrayOf(24286, 32097, 32666),
                            intArrayOf(15958, 29217, 32024),
                            intArrayOf(10207, 24234, 29958),
                            intArrayOf(6929, 18305, 25652),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(4137, 10847, 15682),
                            intArrayOf(17824, 27001, 30058),
                            intArrayOf(10204, 22796, 28291),
                            intArrayOf(6076, 15935, 22125),
                            intArrayOf(3852, 10937, 16816),
                            intArrayOf(2252, 6324, 10131),
                            intArrayOf(25840, 32016, 32662),
                            intArrayOf(15109, 28268, 31531),
                            intArrayOf(9385, 22231, 28340),
                            intArrayOf(6082, 16672, 23479),
                            intArrayOf(3318, 9427, 14681),
                            intArrayOf(30594, 32574, 32718),
                            intArrayOf(16836, 29552, 31859),
                            intArrayOf(9556, 22542, 28356),
                            intArrayOf(6305, 16725, 23540),
                            intArrayOf(3376, 9895, 15184),
                            intArrayOf(29383, 32617, 32745),
                            intArrayOf(18891, 30809, 32401),
                            intArrayOf(11688, 25942, 30687),
                            intArrayOf(7468, 19469, 26651),
                            intArrayOf(3909, 11358, 17012),
                            intArrayOf(31564, 32736, 32748),
                            intArrayOf(20906, 31611, 32600),
                            intArrayOf(13191, 27621, 31537),
                            intArrayOf(8768, 22029, 28676),
                            intArrayOf(5079, 14109, 20906),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576),
                            intArrayOf(8192, 16384, 24576)
                        )
                    )
                )
            )
        )
    }

    /** Initializes `HIGH_TOKEN_CDFS_BY_QCAT` outside the class initializer method-size limit.
     * 
     * @return the initialized `HIGH_TOKEN_CDFS_BY_QCAT` table
     */
    private fun initHighTokenCdfsByQcat(): Array<Array<Array<Array<IntArray>>>> {
        return CoefficientCdfDefaults.inverse5d(
            arrayOf<Array<Array<Array<IntArray>>>>(
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(14298, 20718, 24174),
                            intArrayOf(12536, 19601, 23789),
                            intArrayOf(8712, 15051, 19503),
                            intArrayOf(6170, 11327, 15434),
                            intArrayOf(4742, 8926, 12538),
                            intArrayOf(3803, 7317, 10546),
                            intArrayOf(1696, 3317, 4871),
                            intArrayOf(14392, 19951, 22756),
                            intArrayOf(15978, 23218, 26818),
                            intArrayOf(12187, 19474, 23889),
                            intArrayOf(9176, 15640, 20259),
                            intArrayOf(7068, 12655, 17028),
                            intArrayOf(5656, 10442, 14472),
                            intArrayOf(2580, 4992, 7244),
                            intArrayOf(12136, 18049, 21426),
                            intArrayOf(13784, 20721, 24481),
                            intArrayOf(10836, 17621, 21900),
                            intArrayOf(8372, 14444, 18847),
                            intArrayOf(6523, 11779, 16000),
                            intArrayOf(5337, 9898, 13760),
                            intArrayOf(3034, 5860, 8462)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(15967, 22905, 26286),
                            intArrayOf(13534, 20654, 24579),
                            intArrayOf(9504, 16092, 20535),
                            intArrayOf(6975, 12568, 16903),
                            intArrayOf(5364, 10091, 14020),
                            intArrayOf(4357, 8370, 11857),
                            intArrayOf(2506, 4934, 7218),
                            intArrayOf(23032, 28815, 30936),
                            intArrayOf(19540, 26704, 29719),
                            intArrayOf(15158, 22969, 27097),
                            intArrayOf(11408, 18865, 23650),
                            intArrayOf(8885, 15448, 20250),
                            intArrayOf(7108, 12853, 17416),
                            intArrayOf(4231, 8041, 11480),
                            intArrayOf(19823, 26490, 29156),
                            intArrayOf(18890, 25929, 28932),
                            intArrayOf(15660, 23491, 27433),
                            intArrayOf(12147, 19776, 24488),
                            intArrayOf(9728, 16774, 21649),
                            intArrayOf(7919, 14277, 19066),
                            intArrayOf(5440, 10170, 14185)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(14406, 20862, 24414),
                            intArrayOf(11824, 18907, 23109),
                            intArrayOf(8257, 14393, 18803),
                            intArrayOf(5860, 10747, 14778),
                            intArrayOf(4475, 8486, 11984),
                            intArrayOf(3606, 6954, 10043),
                            intArrayOf(1736, 3410, 5048),
                            intArrayOf(14430, 20046, 22882),
                            intArrayOf(15593, 22899, 26709),
                            intArrayOf(12102, 19368, 23811),
                            intArrayOf(9059, 15584, 20262),
                            intArrayOf(6999, 12603, 17048),
                            intArrayOf(5684, 10497, 14553),
                            intArrayOf(2822, 5438, 7862),
                            intArrayOf(15785, 21585, 24359),
                            intArrayOf(18347, 25229, 28266),
                            intArrayOf(14974, 22487, 26389),
                            intArrayOf(11423, 18681, 23271),
                            intArrayOf(8863, 15350, 20008),
                            intArrayOf(7153, 12852, 17278),
                            intArrayOf(3707, 7036, 9982)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(15460, 21696, 25469),
                            intArrayOf(12170, 19249, 23191),
                            intArrayOf(8723, 15027, 19332),
                            intArrayOf(6428, 11704, 15874),
                            intArrayOf(4922, 9292, 13052),
                            intArrayOf(4139, 7695, 11010),
                            intArrayOf(2291, 4508, 6598),
                            intArrayOf(19856, 26920, 29828),
                            intArrayOf(17923, 25289, 28792),
                            intArrayOf(14278, 21968, 26297),
                            intArrayOf(10910, 18136, 22950),
                            intArrayOf(8423, 14815, 19627),
                            intArrayOf(6771, 12283, 16774),
                            intArrayOf(4074, 7750, 11081),
                            intArrayOf(19852, 26074, 28672),
                            intArrayOf(19371, 26110, 28989),
                            intArrayOf(16265, 23873, 27663),
                            intArrayOf(12758, 20378, 24952),
                            intArrayOf(10095, 17098, 21961),
                            intArrayOf(8250, 14628, 19451),
                            intArrayOf(5205, 9745, 13622)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(10563, 16233, 19763),
                            intArrayOf(9794, 16022, 19804),
                            intArrayOf(6750, 11945, 15759),
                            intArrayOf(4963, 9186, 12752),
                            intArrayOf(3845, 7435, 10627),
                            intArrayOf(3051, 6085, 8834),
                            intArrayOf(1311, 2596, 3830),
                            intArrayOf(11246, 16404, 19689),
                            intArrayOf(12315, 18911, 22731),
                            intArrayOf(10557, 17095, 21289),
                            intArrayOf(8136, 14006, 18249),
                            intArrayOf(6348, 11474, 15565),
                            intArrayOf(5196, 9655, 13400),
                            intArrayOf(2349, 4526, 6587),
                            intArrayOf(13337, 18730, 21569),
                            intArrayOf(19306, 26071, 28882),
                            intArrayOf(15952, 23540, 27254),
                            intArrayOf(12409, 19934, 24430),
                            intArrayOf(9760, 16706, 21389),
                            intArrayOf(8004, 14220, 18818),
                            intArrayOf(4138, 7794, 10961)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(10870, 16684, 20949),
                            intArrayOf(9664, 15230, 18680),
                            intArrayOf(6886, 12109, 15408),
                            intArrayOf(4825, 8900, 12305),
                            intArrayOf(3630, 7162, 10314),
                            intArrayOf(3036, 6429, 9387),
                            intArrayOf(1671, 3296, 4940),
                            intArrayOf(13819, 19159, 23026),
                            intArrayOf(11984, 19108, 23120),
                            intArrayOf(10690, 17210, 21663),
                            intArrayOf(7984, 14154, 18333),
                            intArrayOf(6868, 12294, 16124),
                            intArrayOf(5274, 8994, 12868),
                            intArrayOf(2988, 5771, 8424),
                            intArrayOf(19736, 26647, 29141),
                            intArrayOf(18933, 26070, 28984),
                            intArrayOf(15779, 23048, 27200),
                            intArrayOf(12638, 20061, 24532),
                            intArrayOf(10692, 17545, 22220),
                            intArrayOf(9217, 15251, 20054),
                            intArrayOf(5078, 9284, 12594)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(2331, 3662, 5244),
                            intArrayOf(2891, 4771, 6145),
                            intArrayOf(4598, 7623, 9729),
                            intArrayOf(3520, 6845, 9199),
                            intArrayOf(3417, 6119, 9324),
                            intArrayOf(2601, 5412, 7385),
                            intArrayOf(600, 1173, 1744),
                            intArrayOf(7672, 13286, 17469),
                            intArrayOf(4232, 7792, 10793),
                            intArrayOf(2915, 5317, 7397),
                            intArrayOf(2318, 4356, 6152),
                            intArrayOf(2127, 4000, 5554),
                            intArrayOf(1850, 3478, 5275),
                            intArrayOf(977, 1933, 2843),
                            intArrayOf(18280, 24387, 27989),
                            intArrayOf(15852, 22671, 26185),
                            intArrayOf(13845, 20951, 24789),
                            intArrayOf(11055, 17966, 22129),
                            intArrayOf(9138, 15422, 19801),
                            intArrayOf(7454, 13145, 17456),
                            intArrayOf(3370, 6393, 9013)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(5842, 9229, 10838),
                            intArrayOf(2313, 3491, 4276),
                            intArrayOf(2998, 6104, 7496),
                            intArrayOf(2420, 7447, 9868),
                            intArrayOf(3034, 8495, 10923),
                            intArrayOf(4076, 8937, 10975),
                            intArrayOf(1086, 2370, 3299),
                            intArrayOf(9714, 17254, 20444),
                            intArrayOf(8543, 13698, 17123),
                            intArrayOf(4918, 9007, 11910),
                            intArrayOf(4129, 7532, 10553),
                            intArrayOf(2364, 5533, 8058),
                            intArrayOf(1834, 3546, 5563),
                            intArrayOf(1473, 2908, 4133),
                            intArrayOf(15405, 21193, 25619),
                            intArrayOf(15691, 21952, 26561),
                            intArrayOf(12962, 19194, 24165),
                            intArrayOf(10272, 17855, 22129),
                            intArrayOf(8588, 15270, 20718),
                            intArrayOf(8682, 14669, 19500),
                            intArrayOf(4870, 9636, 13205)
                        )
                    )
                ),
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(14995, 21341, 24749),
                            intArrayOf(13158, 20289, 24601),
                            intArrayOf(8941, 15326, 19876),
                            intArrayOf(6297, 11541, 15807),
                            intArrayOf(4817, 9029, 12776),
                            intArrayOf(3731, 7273, 10627),
                            intArrayOf(1847, 3617, 5354),
                            intArrayOf(14472, 19659, 22343),
                            intArrayOf(16806, 24162, 27533),
                            intArrayOf(12900, 20404, 24713),
                            intArrayOf(9411, 16112, 20797),
                            intArrayOf(7056, 12697, 17148),
                            intArrayOf(5544, 10339, 14460),
                            intArrayOf(2954, 5704, 8319),
                            intArrayOf(12464, 18071, 21354),
                            intArrayOf(15482, 22528, 26034),
                            intArrayOf(12070, 19269, 23624),
                            intArrayOf(8953, 15406, 20106),
                            intArrayOf(7027, 12730, 17220),
                            intArrayOf(5887, 10913, 15140),
                            intArrayOf(3793, 7278, 10447)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(15571, 22232, 25749),
                            intArrayOf(14506, 21575, 25374),
                            intArrayOf(10189, 17089, 21569),
                            intArrayOf(7316, 13301, 17915),
                            intArrayOf(5783, 10912, 15190),
                            intArrayOf(4760, 9155, 13088),
                            intArrayOf(2993, 5966, 8774),
                            intArrayOf(23424, 28903, 30778),
                            intArrayOf(20775, 27666, 30290),
                            intArrayOf(16474, 24410, 28299),
                            intArrayOf(12471, 20180, 24987),
                            intArrayOf(9410, 16487, 21439),
                            intArrayOf(7536, 13614, 18529),
                            intArrayOf(5048, 9586, 13549),
                            intArrayOf(21090, 27290, 29756),
                            intArrayOf(20796, 27402, 30026),
                            intArrayOf(17819, 25485, 28969),
                            intArrayOf(13860, 21909, 26462),
                            intArrayOf(11002, 18494, 23529),
                            intArrayOf(8953, 15929, 20897),
                            intArrayOf(6448, 11918, 16454)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(15999, 22208, 25449),
                            intArrayOf(13050, 19988, 24122),
                            intArrayOf(8594, 14864, 19378),
                            intArrayOf(6033, 11079, 15238),
                            intArrayOf(4554, 8683, 12347),
                            intArrayOf(3672, 7139, 10337),
                            intArrayOf(1900, 3771, 5576),
                            intArrayOf(15788, 21340, 23949),
                            intArrayOf(16825, 24235, 27758),
                            intArrayOf(12873, 20402, 24810),
                            intArrayOf(9590, 16363, 21094),
                            intArrayOf(7352, 13209, 17733),
                            intArrayOf(5960, 10989, 15184),
                            intArrayOf(3232, 6234, 9007),
                            intArrayOf(15761, 20716, 23224),
                            intArrayOf(19318, 25989, 28759),
                            intArrayOf(15529, 23094, 26929),
                            intArrayOf(11662, 18989, 23641),
                            intArrayOf(8955, 15568, 20366),
                            intArrayOf(7281, 13106, 17708),
                            intArrayOf(4248, 8059, 11440)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(14899, 21217, 24503),
                            intArrayOf(13519, 20283, 24047),
                            intArrayOf(9429, 15966, 20365),
                            intArrayOf(6700, 12355, 16652),
                            intArrayOf(5088, 9704, 13716),
                            intArrayOf(4243, 8154, 11731),
                            intArrayOf(2702, 5364, 7861),
                            intArrayOf(22745, 28388, 30454),
                            intArrayOf(20235, 27146, 29922),
                            intArrayOf(15896, 23715, 27637),
                            intArrayOf(11840, 19350, 24131),
                            intArrayOf(9122, 15932, 20880),
                            intArrayOf(7488, 13581, 18362),
                            intArrayOf(5114, 9568, 13370),
                            intArrayOf(20845, 26553, 28932),
                            intArrayOf(20981, 27372, 29884),
                            intArrayOf(17781, 25335, 28785),
                            intArrayOf(13760, 21708, 26297),
                            intArrayOf(10975, 18415, 23365),
                            intArrayOf(9045, 15789, 20686),
                            intArrayOf(6130, 11199, 15423)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(13549, 19724, 23158),
                            intArrayOf(11844, 18382, 22246),
                            intArrayOf(7919, 13619, 17773),
                            intArrayOf(5486, 10143, 13946),
                            intArrayOf(4166, 7983, 11324),
                            intArrayOf(3364, 6506, 9427),
                            intArrayOf(1598, 3160, 4674),
                            intArrayOf(15281, 20979, 23781),
                            intArrayOf(14939, 22119, 25952),
                            intArrayOf(11363, 18407, 22812),
                            intArrayOf(8609, 14857, 19370),
                            intArrayOf(6737, 12184, 16480),
                            intArrayOf(5506, 10263, 14262),
                            intArrayOf(2990, 5786, 8380),
                            intArrayOf(20249, 25253, 27417),
                            intArrayOf(21070, 27518, 30001),
                            intArrayOf(16854, 24469, 28074),
                            intArrayOf(12864, 20486, 25000),
                            intArrayOf(9962, 16978, 21778),
                            intArrayOf(8074, 14338, 19048),
                            intArrayOf(4494, 8479, 11906)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(13960, 19617, 22829),
                            intArrayOf(11150, 17341, 21228),
                            intArrayOf(7150, 12964, 17190),
                            intArrayOf(5331, 10002, 13867),
                            intArrayOf(4167, 7744, 11057),
                            intArrayOf(3480, 6629, 9646),
                            intArrayOf(1883, 3784, 5686),
                            intArrayOf(18752, 25660, 28912),
                            intArrayOf(16968, 24586, 28030),
                            intArrayOf(13520, 21055, 25313),
                            intArrayOf(10453, 17626, 22280),
                            intArrayOf(8386, 14505, 19116),
                            intArrayOf(6742, 12595, 17008),
                            intArrayOf(4273, 8140, 11499),
                            intArrayOf(22120, 27827, 30233),
                            intArrayOf(20563, 27358, 29895),
                            intArrayOf(17076, 24644, 28153),
                            intArrayOf(13362, 20942, 25309),
                            intArrayOf(10794, 17965, 22695),
                            intArrayOf(9014, 15652, 20319),
                            intArrayOf(5708, 10512, 14497)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(5705, 10930, 15725),
                            intArrayOf(7946, 12765, 16115),
                            intArrayOf(6801, 12123, 16226),
                            intArrayOf(5462, 10135, 14200),
                            intArrayOf(4189, 8011, 11507),
                            intArrayOf(3191, 6229, 9408),
                            intArrayOf(1057, 2137, 3212),
                            intArrayOf(10018, 17067, 21491),
                            intArrayOf(7380, 12582, 16453),
                            intArrayOf(6068, 10845, 14339),
                            intArrayOf(5098, 9198, 12555),
                            intArrayOf(4312, 8010, 11119),
                            intArrayOf(3700, 6966, 9781),
                            intArrayOf(1693, 3326, 4887),
                            intArrayOf(18757, 24930, 27774),
                            intArrayOf(17648, 24596, 27817),
                            intArrayOf(14707, 22052, 26026),
                            intArrayOf(11720, 18852, 23292),
                            intArrayOf(9357, 15952, 20525),
                            intArrayOf(7810, 13753, 18210),
                            intArrayOf(3879, 7333, 10328)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(8278, 13242, 15922),
                            intArrayOf(10547, 15867, 18919),
                            intArrayOf(9106, 15842, 20609),
                            intArrayOf(6833, 13007, 17218),
                            intArrayOf(4811, 9712, 13923),
                            intArrayOf(3985, 7352, 11128),
                            intArrayOf(1688, 3458, 5262),
                            intArrayOf(12951, 21861, 26510),
                            intArrayOf(9788, 16044, 20276),
                            intArrayOf(6309, 11244, 14870),
                            intArrayOf(5183, 9349, 12566),
                            intArrayOf(4389, 8229, 11492),
                            intArrayOf(3633, 6945, 10620),
                            intArrayOf(3600, 6847, 9907),
                            intArrayOf(21748, 28137, 30255),
                            intArrayOf(19436, 26581, 29560),
                            intArrayOf(16359, 24201, 27953),
                            intArrayOf(13961, 21693, 25871),
                            intArrayOf(11544, 18686, 23322),
                            intArrayOf(9372, 16462, 20952),
                            intArrayOf(6138, 11210, 15390)
                        )
                    )
                ),
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(16138, 22223, 25509),
                            intArrayOf(15347, 22430, 26332),
                            intArrayOf(9614, 16736, 21332),
                            intArrayOf(6600, 12275, 16907),
                            intArrayOf(4811, 9424, 13547),
                            intArrayOf(3748, 7809, 11420),
                            intArrayOf(2254, 4587, 6890),
                            intArrayOf(15196, 20284, 23177),
                            intArrayOf(18317, 25469, 28451),
                            intArrayOf(13918, 21651, 25842),
                            intArrayOf(10052, 17150, 21995),
                            intArrayOf(7499, 13630, 18587),
                            intArrayOf(6158, 11417, 16003),
                            intArrayOf(4014, 7785, 11252),
                            intArrayOf(15048, 21067, 24384),
                            intArrayOf(18202, 25346, 28553),
                            intArrayOf(14302, 22019, 26356),
                            intArrayOf(10839, 18139, 23166),
                            intArrayOf(8715, 15744, 20806),
                            intArrayOf(7536, 13576, 18544),
                            intArrayOf(5413, 10335, 14498)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(17394, 24501, 27895),
                            intArrayOf(15889, 23420, 27185),
                            intArrayOf(11561, 19133, 23870),
                            intArrayOf(8285, 14812, 19844),
                            intArrayOf(6496, 12043, 16550),
                            intArrayOf(4771, 9574, 13677),
                            intArrayOf(3603, 6830, 10144),
                            intArrayOf(21656, 27704, 30200),
                            intArrayOf(21324, 27915, 30511),
                            intArrayOf(17327, 25336, 28997),
                            intArrayOf(13417, 21381, 26033),
                            intArrayOf(10132, 17425, 22338),
                            intArrayOf(8580, 15016, 19633),
                            intArrayOf(5694, 11477, 16411),
                            intArrayOf(24116, 29780, 31450),
                            intArrayOf(23853, 29695, 31591),
                            intArrayOf(20085, 27614, 30428),
                            intArrayOf(15326, 24335, 28575),
                            intArrayOf(11814, 19472, 24810),
                            intArrayOf(10221, 18611, 24767),
                            intArrayOf(7689, 14558, 20321)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(16214, 22380, 25770),
                            intArrayOf(14213, 21304, 25295),
                            intArrayOf(9213, 15823, 20455),
                            intArrayOf(6395, 11758, 16139),
                            intArrayOf(4779, 9187, 13066),
                            intArrayOf(3821, 7501, 10953),
                            intArrayOf(2293, 4567, 6795),
                            intArrayOf(15859, 21283, 23820),
                            intArrayOf(18404, 25602, 28726),
                            intArrayOf(14325, 21980, 26206),
                            intArrayOf(10669, 17937, 22720),
                            intArrayOf(8297, 14642, 19447),
                            intArrayOf(6746, 12389, 16893),
                            intArrayOf(4324, 8251, 11770),
                            intArrayOf(16532, 21631, 24475),
                            intArrayOf(20667, 27150, 29668),
                            intArrayOf(16728, 24510, 28175),
                            intArrayOf(12861, 20645, 25332),
                            intArrayOf(10076, 17361, 22417),
                            intArrayOf(8395, 14940, 19963),
                            intArrayOf(5731, 10683, 14912)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(14433, 21155, 24938),
                            intArrayOf(14658, 21716, 25545),
                            intArrayOf(9923, 16824, 21557),
                            intArrayOf(6982, 13052, 17721),
                            intArrayOf(5419, 10503, 15050),
                            intArrayOf(4852, 9162, 13014),
                            intArrayOf(3271, 6395, 9630),
                            intArrayOf(22210, 27833, 30109),
                            intArrayOf(20750, 27368, 29821),
                            intArrayOf(16894, 24828, 28573),
                            intArrayOf(13247, 21276, 25757),
                            intArrayOf(10038, 17265, 22563),
                            intArrayOf(8587, 14947, 20327),
                            intArrayOf(5645, 11371, 15252),
                            intArrayOf(22027, 27526, 29714),
                            intArrayOf(23098, 29146, 31221),
                            intArrayOf(19886, 27341, 30272),
                            intArrayOf(15609, 23747, 28046),
                            intArrayOf(11993, 20065, 24939),
                            intArrayOf(9637, 18267, 23671),
                            intArrayOf(7625, 13801, 19144)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(14438, 20798, 24089),
                            intArrayOf(12621, 19203, 23097),
                            intArrayOf(8177, 14125, 18402),
                            intArrayOf(5674, 10501, 14456),
                            intArrayOf(4236, 8239, 11733),
                            intArrayOf(3447, 6750, 9806),
                            intArrayOf(1986, 3950, 5864),
                            intArrayOf(16208, 22099, 24930),
                            intArrayOf(16537, 24025, 27585),
                            intArrayOf(12780, 20381, 24867),
                            intArrayOf(9767, 16612, 21416),
                            intArrayOf(7686, 13738, 18398),
                            intArrayOf(6333, 11614, 15964),
                            intArrayOf(3941, 7571, 10836),
                            intArrayOf(22819, 27422, 29202),
                            intArrayOf(22224, 28514, 30721),
                            intArrayOf(17660, 25433, 28913),
                            intArrayOf(13574, 21482, 26002),
                            intArrayOf(10629, 17977, 22938),
                            intArrayOf(8612, 15298, 20265),
                            intArrayOf(5607, 10491, 14596)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(13569, 19800, 23206),
                            intArrayOf(13128, 19924, 23869),
                            intArrayOf(8329, 14841, 19403),
                            intArrayOf(6130, 10976, 15057),
                            intArrayOf(4682, 8839, 12518),
                            intArrayOf(3656, 7409, 10588),
                            intArrayOf(2577, 5099, 7412),
                            intArrayOf(22427, 28684, 30585),
                            intArrayOf(20913, 27750, 30139),
                            intArrayOf(15840, 24109, 27834),
                            intArrayOf(12308, 20029, 24569),
                            intArrayOf(10216, 16785, 21458),
                            intArrayOf(8309, 14203, 19113),
                            intArrayOf(6043, 11168, 15307),
                            intArrayOf(23166, 28901, 30998),
                            intArrayOf(21899, 28405, 30751),
                            intArrayOf(18413, 26091, 29443),
                            intArrayOf(15233, 23114, 27352),
                            intArrayOf(12683, 20472, 25288),
                            intArrayOf(10702, 18259, 23409),
                            intArrayOf(8125, 14464, 19226)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(9040, 14786, 18360),
                            intArrayOf(9979, 15718, 19415),
                            intArrayOf(7913, 13918, 18311),
                            intArrayOf(5859, 10889, 15184),
                            intArrayOf(4593, 8677, 12510),
                            intArrayOf(3820, 7396, 10791),
                            intArrayOf(1730, 3471, 5192),
                            intArrayOf(11803, 18365, 22709),
                            intArrayOf(11419, 18058, 22225),
                            intArrayOf(9418, 15774, 20243),
                            intArrayOf(7539, 13325, 17657),
                            intArrayOf(6233, 11317, 15384),
                            intArrayOf(5137, 9656, 13545),
                            intArrayOf(2977, 5774, 8349),
                            intArrayOf(21207, 27246, 29640),
                            intArrayOf(19547, 26578, 29497),
                            intArrayOf(16169, 23871, 27690),
                            intArrayOf(12820, 20458, 25018),
                            intArrayOf(10224, 17332, 22214),
                            intArrayOf(8526, 15048, 19884),
                            intArrayOf(5037, 9410, 13118)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(12339, 17329, 20140),
                            intArrayOf(13505, 19895, 23225),
                            intArrayOf(9847, 16944, 21564),
                            intArrayOf(7280, 13256, 18348),
                            intArrayOf(4712, 10009, 14454),
                            intArrayOf(4361, 7914, 12477),
                            intArrayOf(2870, 5628, 7995),
                            intArrayOf(20061, 25504, 28526),
                            intArrayOf(15235, 22878, 26145),
                            intArrayOf(12985, 19958, 24155),
                            intArrayOf(9782, 16641, 21403),
                            intArrayOf(9456, 16360, 20760),
                            intArrayOf(6855, 12940, 18557),
                            intArrayOf(5661, 10564, 15002),
                            intArrayOf(25656, 30602, 31894),
                            intArrayOf(22570, 29107, 31092),
                            intArrayOf(18917, 26423, 29541),
                            intArrayOf(15940, 23649, 27754),
                            intArrayOf(12803, 20581, 25219),
                            intArrayOf(11082, 18695, 23376),
                            intArrayOf(7939, 14373, 19005)
                        )
                    )
                ),
                arrayOf<Array<Array<IntArray>>>(
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(18315, 24289, 27551),
                            intArrayOf(16854, 24068, 27835),
                            intArrayOf(10140, 17927, 23173),
                            intArrayOf(6722, 12982, 18267),
                            intArrayOf(4661, 9826, 14706),
                            intArrayOf(3832, 8165, 12294),
                            intArrayOf(2795, 6098, 9245),
                            intArrayOf(17145, 23326, 26672),
                            intArrayOf(20733, 27680, 30308),
                            intArrayOf(16032, 24461, 28546),
                            intArrayOf(11653, 20093, 25081),
                            intArrayOf(9290, 16429, 22086),
                            intArrayOf(7796, 14598, 19982),
                            intArrayOf(6502, 12378, 17441),
                            intArrayOf(21681, 27732, 30320),
                            intArrayOf(22389, 29044, 31261),
                            intArrayOf(19027, 26731, 30087),
                            intArrayOf(14739, 23755, 28624),
                            intArrayOf(11358, 20778, 25511),
                            intArrayOf(10995, 18073, 24190),
                            intArrayOf(9162, 14990, 20617)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(21425, 27952, 30388),
                            intArrayOf(18062, 25838, 29034),
                            intArrayOf(11956, 19881, 24808),
                            intArrayOf(7718, 15000, 20980),
                            intArrayOf(5702, 11254, 16143),
                            intArrayOf(4898, 9088, 16864),
                            intArrayOf(3679, 6776, 11907),
                            intArrayOf(23294, 30160, 31663),
                            intArrayOf(24397, 29896, 31836),
                            intArrayOf(19245, 27128, 30593),
                            intArrayOf(13202, 19825, 26404),
                            intArrayOf(11578, 19297, 23957),
                            intArrayOf(8073, 13297, 21370),
                            intArrayOf(5461, 10923, 19745),
                            intArrayOf(27367, 30521, 31934),
                            intArrayOf(24904, 30671, 31940),
                            intArrayOf(23075, 28460, 31299),
                            intArrayOf(14400, 23658, 30417),
                            intArrayOf(13885, 23882, 28325),
                            intArrayOf(14746, 22938, 27853),
                            intArrayOf(5461, 16384, 27307)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(18274, 24813, 27890),
                            intArrayOf(15537, 23149, 27003),
                            intArrayOf(9449, 16740, 21827),
                            intArrayOf(6700, 12498, 17261),
                            intArrayOf(4988, 9866, 14198),
                            intArrayOf(4236, 8147, 11902),
                            intArrayOf(2867, 5860, 8654),
                            intArrayOf(17124, 23171, 26101),
                            intArrayOf(20396, 27477, 30148),
                            intArrayOf(16573, 24629, 28492),
                            intArrayOf(12749, 20846, 25674),
                            intArrayOf(10233, 17878, 22818),
                            intArrayOf(8525, 15332, 20363),
                            intArrayOf(6283, 11632, 16255),
                            intArrayOf(20466, 26511, 29286),
                            intArrayOf(23059, 29174, 31191),
                            intArrayOf(19481, 27263, 30241),
                            intArrayOf(15458, 23631, 28137),
                            intArrayOf(12416, 20608, 25693),
                            intArrayOf(10261, 18011, 23261),
                            intArrayOf(8016, 14655, 19666)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(17616, 24586, 28112),
                            intArrayOf(15809, 23299, 27155),
                            intArrayOf(10767, 18890, 23793),
                            intArrayOf(7727, 14255, 18865),
                            intArrayOf(6129, 11926, 16882),
                            intArrayOf(4482, 9704, 14861),
                            intArrayOf(3277, 7452, 11522),
                            intArrayOf(22956, 28551, 30730),
                            intArrayOf(22724, 28937, 30961),
                            intArrayOf(18467, 26324, 29580),
                            intArrayOf(13234, 20713, 25649),
                            intArrayOf(11181, 17592, 22481),
                            intArrayOf(8291, 18358, 24576),
                            intArrayOf(7568, 11881, 14984),
                            intArrayOf(24948, 29001, 31147),
                            intArrayOf(25674, 30619, 32151),
                            intArrayOf(20841, 26793, 29603),
                            intArrayOf(14669, 24356, 28666),
                            intArrayOf(11334, 23593, 28219),
                            intArrayOf(8922, 14762, 22873),
                            intArrayOf(8301, 13544, 20535)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(17113, 23733, 27081),
                            intArrayOf(14139, 21406, 25452),
                            intArrayOf(8552, 15002, 19776),
                            intArrayOf(5871, 11120, 15378),
                            intArrayOf(4455, 8616, 12253),
                            intArrayOf(3469, 6910, 10386),
                            intArrayOf(2255, 4553, 6782),
                            intArrayOf(18224, 24376, 27053),
                            intArrayOf(19290, 26710, 29614),
                            intArrayOf(14936, 22991, 27184),
                            intArrayOf(11238, 18951, 23762),
                            intArrayOf(8786, 15617, 20588),
                            intArrayOf(7317, 13228, 18003),
                            intArrayOf(5101, 9512, 13493),
                            intArrayOf(22639, 28222, 30210),
                            intArrayOf(23216, 29331, 31307),
                            intArrayOf(19075, 26762, 29895),
                            intArrayOf(15014, 23113, 27457),
                            intArrayOf(11938, 19857, 24752),
                            intArrayOf(9942, 17280, 22282),
                            intArrayOf(7167, 13144, 17752)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(15820, 22738, 26488),
                            intArrayOf(13530, 20885, 25216),
                            intArrayOf(8395, 15530, 20452),
                            intArrayOf(6574, 12321, 16380),
                            intArrayOf(5353, 10419, 14568),
                            intArrayOf(4613, 8446, 12381),
                            intArrayOf(3440, 7158, 9903),
                            intArrayOf(24247, 29051, 31224),
                            intArrayOf(22118, 28058, 30369),
                            intArrayOf(16498, 24768, 28389),
                            intArrayOf(12920, 21175, 26137),
                            intArrayOf(10730, 18619, 25352),
                            intArrayOf(10187, 16279, 22791),
                            intArrayOf(9310, 14631, 22127),
                            intArrayOf(24970, 30558, 32057),
                            intArrayOf(24801, 29942, 31698),
                            intArrayOf(22432, 28453, 30855),
                            intArrayOf(19054, 25680, 29580),
                            intArrayOf(14392, 23036, 28109),
                            intArrayOf(12495, 20947, 26650),
                            intArrayOf(12442, 20326, 26214)
                        )
                    ),
                    arrayOf<Array<IntArray>>(
                        arrayOf<IntArray>(
                            intArrayOf(12162, 18785, 22648),
                            intArrayOf(12749, 19697, 23806),
                            intArrayOf(8580, 15297, 20346),
                            intArrayOf(6169, 11749, 16543),
                            intArrayOf(4836, 9391, 13448),
                            intArrayOf(3821, 7711, 11613),
                            intArrayOf(2228, 4601, 7070),
                            intArrayOf(16319, 24725, 28280),
                            intArrayOf(15698, 23277, 27168),
                            intArrayOf(12726, 20368, 25047),
                            intArrayOf(9912, 17015, 21976),
                            intArrayOf(7888, 14220, 19179),
                            intArrayOf(6777, 12284, 17018),
                            intArrayOf(4492, 8590, 12252),
                            intArrayOf(23249, 28904, 30947),
                            intArrayOf(21050, 27908, 30512),
                            intArrayOf(17440, 25340, 28949),
                            intArrayOf(14059, 22018, 26541),
                            intArrayOf(11288, 18903, 23898),
                            intArrayOf(9411, 16342, 21428),
                            intArrayOf(6278, 11588, 15944)
                        ),
                        arrayOf<IntArray>(
                            intArrayOf(13981, 20067, 23226),
                            intArrayOf(16922, 23580, 26783),
                            intArrayOf(11005, 19039, 24487),
                            intArrayOf(7389, 14218, 19798),
                            intArrayOf(5598, 11505, 17206),
                            intArrayOf(6090, 11213, 15659),
                            intArrayOf(3820, 7371, 10119),
                            intArrayOf(21082, 26925, 29675),
                            intArrayOf(21262, 28627, 31128),
                            intArrayOf(18392, 26454, 30437),
                            intArrayOf(14870, 22910, 27096),
                            intArrayOf(12620, 19484, 24908),
                            intArrayOf(9290, 16553, 22802),
                            intArrayOf(6668, 14288, 20004),
                            intArrayOf(27704, 31055, 31949),
                            intArrayOf(24709, 29978, 31788),
                            intArrayOf(21668, 29264, 31657),
                            intArrayOf(18295, 26968, 30074),
                            intArrayOf(16399, 24422, 29313),
                            intArrayOf(14347, 23026, 28104),
                            intArrayOf(12370, 19806, 24477)
                        )
                    )
                )
            )
        )
    }

    /** Returns the coefficient qcat band for one base quantizer index.
     * 
     * @param baseQIndex the base quantizer index in `[0, 256)`
     * @return the coefficient qcat band in `[0, 4)`
     */
    fun qcatForBaseQIndex(baseQIndex: Int): Int {
        val checkedBaseQIndex = checkIndexCompat(baseQIndex, 256)
        return ((if (checkedBaseQIndex > 20) 1 else 0)
                + (if (checkedBaseQIndex > 60) 1 else 0)
                + (if (checkedBaseQIndex > 120) 1 else 0))
    }

    /** Returns a mutable deep copy of the coefficient-skip CDFs for one qcat band.
     * 
     * @param qcat the coefficient qcat band in `[0, 4)`
     * @return a mutable deep copy of the coefficient-skip CDFs
     */
    fun coefficientSkipCdfs(qcat: Int): Array<Array<IntArray>> {
        return deepCopy(COEFFICIENT_SKIP_CDFS_BY_QCAT[checkQcat(qcat)])
    }

    /** Returns a mutable deep copy of the end-of-block prefix CDFs for one qcat band.
     * 
     * @param qcat the coefficient qcat band in `[0, 4)`
     * @return a mutable deep copy of the end-of-block prefix CDFs
     */
    fun endOfBlockPrefixCdfs(qcat: Int): Array<Array<Array<IntArray>>> {
        return deepCopy(END_OF_BLOCK_PREFIX_CDFS_BY_QCAT[checkQcat(qcat)])
    }

    /** Returns a mutable deep copy of the end-of-block base-token CDFs for one qcat band.
     * 
     * @param qcat the coefficient qcat band in `[0, 4)`
     * @return a mutable deep copy of the end-of-block base-token CDFs
     */
    fun endOfBlockBaseTokenCdfs(qcat: Int): Array<Array<Array<IntArray>>> {
        return deepCopy(END_OF_BLOCK_BASE_TOKEN_CDFS_BY_QCAT[checkQcat(qcat)])
    }

    /** Returns a mutable deep copy of the end-of-block high-bit CDFs for one qcat band.
     * 
     * @param qcat the coefficient qcat band in `[0, 4)`
     * @return a mutable deep copy of the end-of-block high-bit CDFs
     */
    fun endOfBlockHighBitCdfs(qcat: Int): Array<Array<Array<IntArray>>> {
        return deepCopy(END_OF_BLOCK_HIGH_BIT_CDFS_BY_QCAT[checkQcat(qcat)])
    }

    /** Returns a mutable deep copy of the base-token CDFs for one qcat band.
     * 
     * @param qcat the coefficient qcat band in `[0, 4)`
     * @return a mutable deep copy of the base-token CDFs
     */
    fun baseTokenCdfs(qcat: Int): Array<Array<Array<IntArray>>> {
        return deepCopy(BASE_TOKEN_CDFS_BY_QCAT[checkQcat(qcat)])
    }

    /** Returns a mutable deep copy of the high-token CDFs for one qcat band.
     * 
     * @param qcat the coefficient qcat band in `[0, 4)`
     * @return a mutable deep copy of the high-token CDFs
     */
    fun highTokenCdfs(qcat: Int): Array<Array<Array<IntArray>>> {
        return deepCopy(HIGH_TOKEN_CDFS_BY_QCAT[checkQcat(qcat)])
    }

    /** Validates one coefficient qcat band.
     * 
     * @param qcat the coefficient qcat band to validate
     * @return the validated coefficient qcat band
     */
    private fun checkQcat(qcat: Int): Int {
        return checkIndexCompat(qcat, QCAT_COUNT)
    }

    /** Converts raw `dav1d` CDF thresholds into inverse-ordered AV1 CDF arrays with a zero count slot.
     * 
     * @param rawCdf the raw threshold values copied from `dav1d`
     * @return the transformed inverse-ordered AV1 CDF array
     */
    private fun inverse(vararg rawCdf: Int): IntArray {
        val transformed = IntArray(rawCdf.size + 1)
        for (i in rawCdf.indices) {
            transformed[i] = 32768 - rawCdf[i]
        }
        return transformed
    }

    /** Converts a 2-dimensional table of raw `dav1d` thresholds into inverse-ordered AV1 CDF arrays.
     * 
     * @param rawCdfs the 2-dimensional table of raw threshold values
     * @return the transformed inverse-ordered AV1 CDF arrays
     */
    private fun inverse2d(rawCdfs: Array<IntArray>): Array<IntArray> {
        val transformed: Array<IntArray> = arrayOfNullsCompat<IntArray>(rawCdfs.size)
        for (i in rawCdfs.indices) {
            transformed[i] = inverse(*rawCdfs[i])
        }
        return transformed
    }

    /** Converts a 3-dimensional table of raw `dav1d` thresholds into inverse-ordered AV1 CDF arrays.
     * 
     * @param rawCdfs the 3-dimensional table of raw threshold values
     * @return the transformed inverse-ordered AV1 CDF arrays
     */
    private fun inverse3d(rawCdfs: Array<Array<IntArray>>): Array<Array<IntArray>> {
        val transformed: Array<Array<IntArray>> = arrayOfNullsCompat<Array<IntArray>>(rawCdfs.size)
        for (i in rawCdfs.indices) {
            transformed[i] = inverse2d(rawCdfs[i])
        }
        return transformed
    }

    /** Converts a 4-dimensional table of raw `dav1d` thresholds into inverse-ordered AV1 CDF arrays.
     * 
     * @param rawCdfs the 4-dimensional table of raw threshold values
     * @return the transformed inverse-ordered AV1 CDF arrays
     */
    private fun inverse4d(rawCdfs: Array<Array<Array<IntArray>>>): Array<Array<Array<IntArray>>> {
        val transformed: Array<Array<Array<IntArray>>> =
            arrayOfNullsCompat<Array<Array<IntArray>>>(rawCdfs.size)
        for (i in rawCdfs.indices) {
            transformed[i] = inverse3d(rawCdfs[i])
        }
        return transformed
    }

    /** Converts a 5-dimensional table of raw `dav1d` thresholds into inverse-ordered AV1 CDF arrays.
     * 
     * @param rawCdfs the 5-dimensional table of raw threshold values
     * @return the transformed inverse-ordered AV1 CDF arrays
     */
    private fun inverse5d(rawCdfs: Array<Array<Array<Array<IntArray>>>>): Array<Array<Array<Array<IntArray>>>> {
        val transformed: Array<Array<Array<Array<IntArray>>>> =
            arrayOfNullsCompat<Array<Array<Array<IntArray>>>>(rawCdfs.size)
        for (i in rawCdfs.indices) {
            transformed[i] = inverse4d(rawCdfs[i])
        }
        return transformed
    }

    /** Creates a copy of a one-dimensional integer table.
     * 
     * @param source the source table to copy
     * @return a copy of the supplied table
     */
    private fun deepCopy(source: IntArray): IntArray {
        return source.copyOf()
    }

    /** Creates a deep copy of a 2-dimensional integer table.
     * 
     * @param source the source table to copy
     * @return a deep copy of the supplied table
     */
    private fun deepCopy(source: Array<IntArray>): Array<IntArray> {
        val copy: Array<IntArray> = arrayOfNullsCompat<IntArray>(source.size)
        for (i in source.indices) {
            copy[i] = deepCopy(source[i])
        }
        return copy
    }

    /** Creates a deep copy of a 3-dimensional integer table.
     * 
     * @param source the source table to copy
     * @return a deep copy of the supplied table
     */
    private fun deepCopy(source: Array<Array<IntArray>>): Array<Array<IntArray>> {
        val copy: Array<Array<IntArray>> = arrayOfNullsCompat<Array<IntArray>>(source.size)
        for (i in source.indices) {
            copy[i] = deepCopy(source[i])
        }
        return copy
    }

    /** Creates a deep copy of a 4-dimensional integer table.
     * 
     * @param source the source table to copy
     * @return a deep copy of the supplied table
     */
    private fun deepCopy(source: Array<Array<Array<IntArray>>>): Array<Array<Array<IntArray>>> {
        val copy: Array<Array<Array<IntArray>>> =
            arrayOfNullsCompat<Array<Array<IntArray>>>(source.size)
        for (i in source.indices) {
            copy[i] = deepCopy(source[i])
        }
        return copy
    }
}
