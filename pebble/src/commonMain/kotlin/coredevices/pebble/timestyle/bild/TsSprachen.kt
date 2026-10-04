package coredevices.pebble.timestyle.bild

/*
 * Tag-, Monats- und Wochenwoerter von TimeStyle in allen 37 Sprachen - eine
 * Abschrift von src/fw/apps/watch/timestyle/languages.c (dort aus
 * TimeStylePebble e2be725). Die Reihenfolge ist die Sprach-ID der Firmware.
 * TsSprachenUndMischungTest vergleicht jeden Eintrag mit der C-Datei.
 */
internal object TsSprachen {
    val TAGE: Array<Array<String>> = arrayOf(
        arrayOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"),
        arrayOf("DIM", "LUN", "MAR", "MER", "JEU", "VEN", "SAM"),
        arrayOf("SO", "MO", "DI", "MI", "DO", "FR", "SA"),
        arrayOf("DOM", "LUN", "MAR", "MIÉ", "JUE", "VIE", "SÁB"),
        arrayOf("DOM", "LUN", "MAR", "MER", "GIO", "VEN", "SAB"),
        arrayOf("ZO", "MA", "DI", "WO", "DO", "VR", "ZA"),
        arrayOf("PAZ", "PTS", "SAL", "ÇAR", "PER", "CUM", "CTS"),
        arrayOf("NE", "PO", "ÚT", "ST", "ČT", "PÁ", "SO"),
        arrayOf("DOM", "SEG", "TER", "QUA", "QUI", "SEX", "SÁB"),
        arrayOf("ΚΥΡ", "ΔΕΥ", "ΤΡΙ", "ΤΕΤ", "ΠΕΜ", "ΠΑΡ", "ΣΑΒ"),
        arrayOf("SÖN", "MÅN", "TIS", "ONS", "TOR", "FRE", "LÖR"),
        arrayOf("NDZ", "PON", "WTO", "ŚRO", "CZW", "PIĄ", "SOB"),
        arrayOf("NE", "PO", "UT", "ST", "ŠT", "PI", "SO"),
        arrayOf("CN", "T2", "T3", "T4", "T5", "T6", "T7"),
        arrayOf("DUM", "LUN", "MAR", "MIE", "JOI", "VIN", "SÂM"),
        arrayOf("DG", "DL", "DT", "DC", "DJ", "DV", "DS"),
        arrayOf("SØN", "MAN", "TIR", "ONS", "TOR", "FRE", "LØR"),
        arrayOf("ВС", "ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ"),
        arrayOf("P", "E", "T", "K", "N", "R", "L"),
        arrayOf("IG", "AL", "AR", "AZ", "OG", "OL", "LR"),
        arrayOf("SU", "MA", "TI", "KE", "TO", "PE", "LA"),
        arrayOf("SØN", "MAN", "TIR", "ONS", "TOR", "FRE", "LØR"),
        arrayOf("SEK", "PIR", "ANT", "TRE", "KET", "PEN", "ŠEŠ"),
        arrayOf("NED", "PON", "TOR", "SRE", "ČET", "PET", "SOB"),
        arrayOf("VAS", "HÉT", "KED", "SZE", "CSÜ", "PÉN", "SZO"),
        arrayOf("NED", "PON", "UTO", "SRE", "ČET", "PET", "SUB"),
        arrayOf("DOM", "LUA", "MÁI", "CÉA", "DÉA", "AOI", "SAT"),
        arrayOf("SVĒ", "PIR", "OTR", "TRE", "CET", "PIE", "SES"),
        arrayOf("NE", "PO", "UT", "SR", "ČE", "PE", "SU"),
        arrayOf("日", "一", "二", "三", "四", "五", "六"),
        arrayOf("MIN", "SEN", "SEL", "RAB", "KAM", "JUM", "SAB"),
        arrayOf("НД", "ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ"),
        arrayOf("SUL", "LLN", "MAW", "MER", "IAU", "GWE", "SAD"),
        arrayOf("DOM", "LUN", "MAR", "MER", "XOV", "VEN", "SAB"),
        arrayOf("日", "月", "火", "水", "木", "金", "土"),
        arrayOf("일", "월", "화", "수", "목", "금", "토"),
        arrayOf("א", "ב", "ג", "ד", "ה", "ו", "ש"),
    )

    val MONATE: Array<Array<String>> = arrayOf(
        arrayOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"),
        arrayOf("JAN", "FÉV", "MAR", "AVR", "MAI", "JUI", "JUL", "AOÛ", "SEP", "OCT", "NOV", "DÉC"),
        arrayOf("JAN", "FEB", "MÄR", "APR", "MAI", "JUN", "JUL", "AUG", "SEP", "OKT", "NOV", "DEZ"),
        arrayOf("ENE", "FEB", "MAR", "ABR", "MAY", "JUN", "JUL", "AGO", "SEP", "OCT", "NOV", "DIC"),
        arrayOf("GEN", "FEB", "MAR", "APR", "MAG", "GIU", "LUG", "AGO", "SET", "OTT", "NOV", "DIC"),
        arrayOf("JAN", "FEB", "MRT", "APR", "MEI", "JUN", "JUL", "AUG", "SEP", "OKT", "NOV", "DEC"),
        arrayOf("OCA", "ŞUB", "MAR", "NİS", "MAY", "HAZ", "TEM", "AĞU", "EYL", "EKİ", "KAS", "ARA"),
        arrayOf("LED", "ÚNO", "BŘE", "DUB", "KVĚ", "ČRV", "ČVC", "SRP", "ZÁŘ", "ŘÍJ", "LIS", "PRO"),
        arrayOf("JAN", "FEV", "MAR", "ABR", "MAI", "JUN", "JUL", "AGO", "SET", "OUT", "NOV", "DEZ"),
        arrayOf("ΙΑΝ", "ΦΕΒ", "ΜΑΡ", "ΑΠΡ", "ΜΑΪ", "ΙΟΝ", "ΙΟΛ", "ΑΥΓ", "ΣΕΠ", "ΟΚΤ", "ΝΟΕ", "ΔΕΚ"),
        arrayOf("JAN", "FEB", "MAR", "APR", "MAJ", "JUN", "JUL", "AUG", "SEP", "OKT", "NOV", "DEC"),
        arrayOf("STY", "LUT", "MAR", "KWI", "MAJ", "CZE", "LIP", "SIE", "WRZ", "PAŹ", "LIS", "GRU"),
        arrayOf("JAN", "FEB", "MAR", "APR", "MÁJ", "JÚN", "JÚL", "AUG", "SEP", "OKT", "NOV", "DEC"),
        arrayOf("Th1", "Th2", "Th3", "Th4", "Th5", "Th6", "Th7", "Th8", "Th9", "T10", "T11", "T12"),
        arrayOf("IAN", "FEB", "MAR", "APR", "MAI", "IUN", "IUL", "AUG", "SEP", "OCT", "NOI", "DEC"),
        arrayOf("GEN", "FEB", "MAR", "ABR", "MAI", "JUN", "JUL", "AGO", "SET", "OCT", "NOV", "DES"),
        arrayOf("JAN", "FEB", "MAR", "APR", "MAI", "JUN", "JUL", "AUG", "SEP", "OKT", "NOV", "DES"),
        arrayOf("ЯНВ", "ФЕВ", "МАР", "АПР", "МАЙ", "ИЮН", "ИЮЛ", "АВГ", "СЕН", "ОКТ", "НОЯ", "ДЕК"),
        arrayOf("JAN", "VEB", "MÄR", "APR", "MAI", "JUN", "JUL", "AUG", "SEP", "OKT", "NOV", "DET"),
        arrayOf("URT", "OTS", "MAR", "API", "MAI", "EKA", "UZT", "ABU", "IRA", "URR", "AZA", "ABE"),
        arrayOf("TAM", "HEL", "MAA", "HUH", "TOU", "KES", "HEI", "ELO", "SYY", "LOK", "MAR", "JOU"),
        arrayOf("JAN", "FEB", "MAR", "APR", "MAJ", "JUN", "JUL", "AUG", "SEP", "OKT", "NOV", "DEC"),
        arrayOf("SAU", "VAS", "KOV", "BAL", "GEG", "BIR", "LIE", "RUG", "RGS", "SPA", "LAP", "GRU"),
        arrayOf("JAN", "FEB", "MAR", "APR", "MAJ", "JUN", "JUL", "AVG", "SEP", "OKT", "NOV", "DEC"),
        arrayOf("JAN", "FEB", "MÁR", "ÁPR", "MÁJ", "JÚN", "JÚL", "AUG", "SZE", "OKT", "NOV", "DEC"),
        arrayOf("SIJ", "VEL", "OŽU", "TRA", "SVI", "LIP", "SRP", "KOL", "RUJ", "LIS", "STU", "PRO"),
        arrayOf("EAN", "FEA", "MÁR", "AIB", "BEA", "MEI", "IÚI", "LÚN", "MFÓ", "DFÓ", "SAM", "NOL"),
        arrayOf("JAN", "FEB", "MAR", "APR", "MAI", "JŪN", "JŪL", "AUG", "SEP", "OKT", "NOV", "DEC"),
        arrayOf("JAN", "FEB", "MAR", "APR", "MAJ", "JUN", "JUL", "AVG", "SEP", "OKT", "NOV", "DEC"),
        arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"),
        arrayOf("JAN", "FEB", "MAR", "APR", "MEI", "JUN", "JUL", "AGU", "SEP", "OKT", "NOV", "DES"),
        arrayOf("СІЧ", "ЛЮТ", "БЕР", "КВІ", "ТРА", "ЧЕР", "ЛИП", "СЕР", "ВЕР", "ЖОВ", "ЛИС", "ГРУ"),
        arrayOf("ION", "CHW", "MAW", "EBR", "MAI", "MEH", "GOR", "AWS", "MED", "HYD", "TCH", "RHA"),
        arrayOf("XAN", "FEB", "MAR", "ABR", "MAI", "XUÑ", "XUL", "AGO", "SET", "OUT", "NOV", "NAD"),
        arrayOf("1月", "2月", "3月", "4月", "5月", "6月", "7月", "8月", "9月", "10月", "11月", "12月"),
        arrayOf("1월", "2월", "3월", "4월", "5월", "6월", "7월", "8월", "9월", "10월", "11월", "12월"),
        arrayOf("ינו", "פבר", "מרץ", "אפר", "מאי", "יונ", "יול", "אוג", "ספט", "אוק", "נוב", "דצמ"),
    )

    val WOCHE: Array<String> = arrayOf(
        "Wk", "Sem", "KW", "Sem", "Sett", "Wk", "Hf", "Týd", "Sem", "εβδ", "V", "Tydz", "Týž", "Tuần",
        "Săpt", "Setm", "Uke", "нед", "Näd", "Ast", "Vk", "Uge", "Sav", "Ted", "Hét", "Tj", "Scht", "Ned",
        "Ned", "周", "Ming", "Тиж", "Wnos", "Sem", "週", "주", "שב",
    )

    /** Die Sprache Tuerkisch (Prozentzeichen vor der Zahl). */
    const val TUERKISCH = 6
}
