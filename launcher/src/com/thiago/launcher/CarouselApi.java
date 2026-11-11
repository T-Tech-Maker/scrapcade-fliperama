package com.thiago.launcher;

import android.view.View;
import java.util.List;

/** O que a tela inicial precisa do carrossel de capas, seja desenhado pela GPU ou pela CPU. */
interface CarouselApi {
    interface Listener {
        void onPick(Catalog.Game g);
        /** A posicao mudou por toque na tela: as teclas ja sao tratadas por quem chamou move(). */
        void onMoved();
    }

    void setListener(Listener l);
    void setItems(List<Catalog.Game> l, int sel);
    void clearCache();
    void move(int dir);
    Catalog.Game current();
    /** Posicao de current() na lista, ou -1 se a lista esta vazia. */
    int index();
    void setAnimMs(int ms);
    void setShowCovers(boolean on);
    /** Mostrar atras das capas uma cena do jogo selecionado. */
    void setShowBackdrop(boolean on);
    View view();
}
