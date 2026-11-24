package com.audiobook.server.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LanguageDetector} 的表驱动测试。
 *
 * <p>样本都取「书级长度」的成段文字（而非单句），因为识别本就是书级投票；单句不足以稳定区分
 * 法/西/意/葡，测试若用单句反而会自证其短。日/韩等 Pocket 不支持的语种必须落 {@code other}，
 * 这是它「不误发给没音色的 Pocket 节点」的关键安全属性。
 */
class LanguageDetectorTest {

    @Test
    @DisplayName("中文：纯汉字成段 → zh")
    void detectsChinese() {
        String text = "他慢慢走进房间，窗外的雨还在下着。她轻声说，我们该出发了。"
                + "这是一本关于时间与记忆的小说，读起来让人心里发酸，久久不能平静。";
        assertThat(LanguageDetector.detect(text)).isEqualTo(LanguageDetector.ZH);
    }

    @Test
    @DisplayName("英文：the/and/it/was/they 高频 → en")
    void detectsEnglish() {
        String text = "The quick brown fox jumps over the lazy dog, and the children were playing "
                + "in the garden. It was not the first time that they had seen such a beautiful "
                + "sunrise, and they would talk about it for the rest of their lives.";
        assertThat(LanguageDetector.detect(text)).isEqualTo("en");
    }

    @Test
    @DisplayName("法文：le/la/que/dans/nous/pas 高频 → fr")
    void detectsFrench() {
        String text = "Le chat est sur la table et la femme dit que nous ne pouvons pas aller "
                + "dans le jardin parce que le temps n'est pas bon aujourd'hui. Les enfants "
                + "jouent avec un ballon dans la rue pendant que le chien court.";
        assertThat(LanguageDetector.detect(text)).isEqualTo("fr");
    }

    @Test
    @DisplayName("德文：der/die/und/das/nicht + ß → de")
    void detectsGerman() {
        String text = "Der Hund läuft durch den Garten und die Kinder spielen mit dem Ball. "
                + "Ich weiß nicht, ob das Wetter heute gut wird, aber wir gehen trotzdem nach "
                + "draußen. Das Buch auf dem Tisch ist sehr interessant und ich habe es gelesen.";
        assertThat(LanguageDetector.detect(text)).isEqualTo("de");
    }

    @Test
    @DisplayName("西班牙文：el/que/no/está/muy + ñ → es")
    void detectsSpanish() {
        String text = "El niño comió un pescado a la brasa y su amigo le dijo que no quería comer "
                + "más porque estaba muy cansado. La señora dejó lasañas frías sobre la mesa, y "
                + "entonces todos los hombres comieron sin hablar durante la cena.";
        assertThat(LanguageDetector.detect(text)).isEqualTo("es");
    }

    @Test
    @DisplayName("意大利文：il/che/della/perché/sono/loro → it")
    void detectsItalian() {
        String text = "Il bambino ha mangiato una mela e la donna ha detto che non voleva uscire "
                + "perché pioveva forte. La ragazza italiana ha scritto una lettera per il suo "
                + "amico, e loro sono andati al mare insieme alla fine della settimana.";
        assertThat(LanguageDetector.detect(text)).isEqualTo("it");
    }

    @Test
    @DisplayName("葡萄牙文：o/a/não/que/está + ã → pt")
    void detectsPortuguese() {
        String text = "O menino comeu o pão com manteiga e a menina disse que não queria ir para "
                + "a escola porque estava chovendo muito. Nós vamos à praia amanhã se o tempo "
                + "estiver bom, e as crianças vão brincar na areia o dia todo.";
        assertThat(LanguageDetector.detect(text)).isEqualTo("pt");
    }

    @Test
    @DisplayName("荷兰文：de/het/en/van/dat/naar → nl")
    void detectsDutch() {
        String text = "De kat slaapt op de bank en de hond ligt voor de deur. Ik denk dat het "
                + "morgen gaat regenen, maar wij gaan toch naar het park omdat de kinderen "
                + "willen spelen. De kinderen zijn erg blij met het weer vandaag.";
        assertThat(LanguageDetector.detect(text)).isEqualTo("nl");
    }

    @Test
    @DisplayName("日文（含假名）不落 zh，且 Pocket 不支持 → other")
    void japaneseFallsBackToOther() {
        String text = "彼はゆっくり部屋に入り、窓の外ではまだ雨が降っていた。彼女は静かに、"
                + "私たちはそろそろ出発すべきだと言った。これは時間と記憶についての小説である。";
        String detected = LanguageDetector.detect(text);
        assertThat(detected).isEqualTo(LanguageDetector.OTHER);
        assertThat(LanguageDetector.isPocketRoutable(detected)).isFalse();
    }

    @Test
    @DisplayName("null/空白/过短：一律 other，绝不硬猜")
    void emptyAndTooShortAreOther() {
        assertThat(LanguageDetector.detect(null)).isEqualTo(LanguageDetector.OTHER);
        assertThat(LanguageDetector.detect("   ")).isEqualTo(LanguageDetector.OTHER);
        assertThat(LanguageDetector.detect("Hello")).isEqualTo(LanguageDetector.OTHER);
    }

    @Test
    @DisplayName("isPocketRoutable 只认英/法/德/西/意/葡/荷")
    void pocketRoutableSet() {
        assertThat(LanguageDetector.isPocketRoutable("en")).isTrue();
        assertThat(LanguageDetector.isPocketRoutable("nl")).isTrue();
        assertThat(LanguageDetector.isPocketRoutable("zh")).isFalse();
        assertThat(LanguageDetector.isPocketRoutable("other")).isFalse();
        assertThat(LanguageDetector.isPocketRoutable("ja")).isFalse();
        assertThat(LanguageDetector.isPocketRoutable(null)).isFalse();
    }
}
