import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.ThreadLocalRandom;

public class Main {  //wyniki: https://docs.google.com/spreadsheets/d/1QiDLjMX_lORQf7Wva6eKuCVYIUOWK37q4Ch1MktIQm0
    static int liczbaRequestow = 10000; // na proces
    static int rozmiarPamieciWirtualnej = 500; // Maksymalna na proces
    static int rozmiarPamieciFizycznej = 100; // Łączna pamięć, dla wszystkich procesów
    static int iloscProcesow = 10;
    private static int zatrzymania = 0;
    private static LRU maxWSSProcess;
    private static LRU minPausedProcess;
    static ArrayList<LRU> simList = new ArrayList<>();
    public static void main(String[] args) {

        generateProcesses(); // TODO, rozdzielić na osobne pliki.

        System.out.println("Przydział równy: "+runEqual());
        simList.forEach(LRU::reset); // Resetuje wszystkie statystyki symulacji przed kolejnym uruchomieniem
        System.out.println("Proporcjonalny:  "+runProportional());
        simList.forEach(LRU::reset);
        System.out.println("Model strefowy:  "+runZoning(20));
        /*
        Zwiększanie parametru t dla modelu strefowego doprowadzi do wyliczania większych WSS dla każdego procesu:
        (Więcej zmian zakresów lokalności - wykorzystanie większej ilości różnych ramek).
        Przez co więcej procesów będzie zatrzymanych, co zmniejszy ilość błędów, ale zwiększy czas wykonania.
         */
        System.out.println("ilość zatrzymań procesów: " + zatrzymania);
        simList.forEach(LRU::reset);
        System.out.println("Sterowanie PFF:  "+runErrorFrequency(50,0.9,0.1));
        System.out.println("ilość zatrzymań procesów: " + zatrzymania);


    }

    public static void generateProcesses() {
        // Generuje losowe procesy: ich ciągi odwołań i symulacje.
        RequestGenerator requesty = new RequestGenerator();
        ArrayList<ArrayList<Integer>> processesRequests = new ArrayList<>();
        for (int i = 0; i < iloscProcesow; i++) {
            int processRange = ThreadLocalRandom.current().nextInt(rozmiarPamieciWirtualnej /4, rozmiarPamieciWirtualnej); // Losowy zakres każdego procesu
            processesRequests.add(requesty.generateRequests(processRange*i, processRange*(i+1), liczbaRequestow));
            simList.add(new LRU(rozmiarPamieciFizycznej/iloscProcesow, processesRequests.get(i))); // Domyślny przydział ramek, potem zmieniany przez algorytmy przydziału
        }
    }

    public static int runEqual() {
        //Równy podział ramek, procesy mają tyle samo ramek, o ile pamięć fizyczna dzieli się po równo.
        int assignedFrames = rozmiarPamieciFizycznej/iloscProcesow;
        int remainingFrames = rozmiarPamieciFizycznej;
        int errors = 0;
        for (int j = 0; j < liczbaRequestow; j++) { // Dla każdego procesu wykonuję jeden request, tyle razy ile jest procesów

            for (LRU sim : simList) {
                if (j==0) { // Na pierwszej iteracji przydzielam ramki
                    sim.physicalMemorySize = assignedFrames;
                    remainingFrames -= assignedFrames;
                }
                else if (remainingFrames > 0) { // Przydzielam procesom pozostałe ramki (zostaną jeżeli jest reszta z dzielenia)
                    sim.physicalMemorySize++;
                    remainingFrames--;
                }

                sim.doLRU(); // Wykonuje request

                if (j == liczbaRequestow-1) { // Na ostatniej iteracji zliczam błędy
                    errors += sim.errors;
                }
            }
        }
        return errors;
    }

    public static int runProportional() {
        // Przydział proporcjonalny: procesy mają przydzieloną ilość ramek proporcjonalną do ich ilości stron
        int assignedFrames;
        int remainingFrames = rozmiarPamieciFizycznej;
        int totalFramesUsed = 0;
        int errors = 0;
        for (int j = 0; j < liczbaRequestow; j++) { // Dla każdego procesu wykonuję jeden request, tyle razy ile jest procesów

            for (LRU sim : simList) {
                if (j==0) { // Na pierwszej iteracji zliczam łączną liczbę ramek
                    totalFramesUsed += sim.range;

                }
                else if (j==1) { // Przydzielam procesom ramki proporcjonalnie do ich zakresu
                    double percentage = (sim.range/totalFramesUsed*100);
                    assignedFrames = Math.max((int) (rozmiarPamieciFizycznej/100*percentage),1);
                    sim.physicalMemorySize = assignedFrames;
                    remainingFrames -= assignedFrames;
                }
                else if (remainingFrames > 0) { // Przydzielam procesom pozostałe ramki
                    //System.out.println(remainingFrames);
                    sim.physicalMemorySize++;
                    remainingFrames--;

                }

                sim.doLRU(); // Wykonuje request

                if (j == liczbaRequestow-1) { // Na ostatniej iteracji zliczam błędy
                    errors += sim.errors;
                    //System.out.println(sim.physicalMemorySize);

                }
            }
        }
        return errors;
    }

    public static int runZoning(int t) {
        ArrayList<LRU> doneProcesses = new ArrayList<>();
        zatrzymania = 0;
        final int[] errors = new int[1]; // lambda wymaga stałych, więc używam stałej tablicy ze zmiennym elementem ;)
        int c = t/2;
        int totalFramesUsed = 0;
        ArrayList<LRU> simListCopy = new ArrayList<>(simList);
        // Początkowo ramki są rozdzielone równo pomiędzy procesami, nie mogę obliczyć WSS procesu zanim ten zacznie się wykonywać.
        // Pierwsze t iteracji: dodaje requesty do HashSet WorkingSet. (HashSet bo nie interesują mnie powtórki), rozmiar HashSet to WSS
        // pętla: (jeżeli lista procesów .isEmpty(), break)
        // Jeżeli mam miejsce wznawiam proces.
        // While (suma WSS-ów > rozmiar pamięci fizycznej) Ustawiam WSS procesu o największym WSS na 0 (wstrzymuje)
        // Następnie proporcjonalnie zwiększam WSS pozostałych procesów tak aby wykorzystywały wszystkie dostępne ramki.
        // Przydzielam procesom tyle ramek, ile wynosi ich WSS (WSS=0 oznacza proces wstrzymany)
        // Usuwam z HashSet c najstarszych elementów (czyszczę WorkingSet z poprzednich requestów)
        // c razy wykonuję requesty, sprawdzając czy current request nie jest na końcu zakresu.
        // Jeżeli current request jakiegoś procesu jest na końcu listy, usuwam go z listy procesów
        // dla każdego niewstrzymanego procesu: WSS = rozmiar WorkingSet
        // goto pętla

        for (int i = 0; i < t; i++) {
            for (LRU sim : simList) {
                sim.doLRU(); // Buduję WorkingSet wykonując początkowe t requestów
                sim.WSS = sim.workingSet.size();
                if (i == 0) { totalFramesUsed += sim.range; } // Zliczam łączną ilość ramek do przydziału proporcjonalnego
            }
        }

        while (!simList.isEmpty()) {

            while (freeFrames() < 0) {
                // Jeżeli nie ma wystarczająco pamięci fizycznej wstrzymuję procesy o największym WSS
                maxWSSProcess.WSS = 0;
                zatrzymania++;
                //System.out.println("Wstrzymuję proces");
            }

            // Niemożliwa jest sytacja, gdzie wznowiłbym dopiero co wstrzymany proces, nie mam na niego miejsca.
            totalWSS(); // Odświeżam wskaźnik na zatrzymany proces o najmniejszym WSS;
            while(minPausedProcess != null && minPausedProcess.workingSet.size() <= freeFrames()) {
                // jeżeli jest jakiś wstrzymany proces, sprawdzam czy mamy na niego miejsce, jeśli tak to go wznawiam.
                if (minPausedProcess == null) continue;
                //System.out.println("Wznawiam proces");
                minPausedProcess.WSS = minPausedProcess.workingSet.size();
            }

            //Przydział proporcjonalny pozostałych ramek:
            while (freeFrames() > 0) {
                for (LRU sim : simList) {
                    if (sim.WSS != 0 && freeFrames() > 0) {
                        double percentage = (sim.range/totalFramesUsed*100);
                        sim.WSS += Math.max((int) (freeFrames()/100.0*percentage),1);
                    }
                }
                //Przydział ewentualnych pozostałych ramek
                for (int i = 0; i < simList.size() && freeFrames() > 0; i++) {
                    if (simList.get(i).WSS != 0) { simList.get(i).WSS++; }
                }
            }




            for (LRU sim : simList) {
                if (sim.WSS == 0) continue; // Jeżeli proces jest wstrzymany, ignoruję go.
                // Przydzielam procesom tyle ramek, ile wynosi ich WSS (WSS=0 oznacza proces wstrzymany)
                sim.setMemorySize(sim.WSS);
                // Następnie usuwam z HashSet c najstarszych elementów (czyszczę WorkingSet z poprzednich requestów)
                Iterator<Integer> iter = sim.workingSet.iterator();
                for (int j = 0; j < c; j++) {
                    if (iter.hasNext()) {
                        iter.next();
                        iter.remove();
                    }
                }
            }
            for (LRU sim: simList) {
                // c razy wykonuję requesty, sprawdzając czy current request nie jest na końcu zakresu.
                if (sim.WSS == 0) continue; // Jeżeli proces jest wstrzymany, nie wykonuję jego requestów
                for (int j = 0; j < c; j++) {
                    sim.doLRU();
                    if (sim.currentRequestIndex >= liczbaRequestow-1) {
                        // Jeżeli jakiś proces skończył wykonywać wszystkie swoje requesty, oznaczam go jako wykonany.
                        doneProcesses.add(sim);
                        break;
                    }
                }
            }
            doneProcesses.forEach(simList::remove); // Usuwam wykonane procesy z głównej listy.

            for (LRU sim : simList) {
                // dla każdego niewstrzymanego procesu: WSS = rozmiar WorkingSet
                if (sim.WSS != 0) {
                    sim.WSS = sim.workingSet.size();
                }
            }


        }
        doneProcesses.forEach(LRU -> errors[0] += LRU.errors);
        simList = simListCopy; // Przywracam oryginalną listę procesów
        return errors[0];
    }
    public static int totalWSS() {
        int totalWSS = 0;
        int maxWSS = 0;
        int minWSS = Integer.MAX_VALUE;
        for (LRU sim : simList) {
            totalWSS += sim.WSS;
            if (sim.WSS > maxWSS) {
                maxWSS = sim.WSS;
                maxWSSProcess = sim;
            }
            if (sim.WSS == 0 && sim.workingSet.size() < minWSS) {
                minWSS = sim.WSS;
                minPausedProcess = sim;

            }
        }
        if (minWSS == Integer.MAX_VALUE) {
            // Jeżeli żaden proces nie jest wstrzymany
            minPausedProcess = null;
        }
        return totalWSS;
    }
    public static int freeFrames() {
        return rozmiarPamieciFizycznej - totalWSS();
    }

    public static int runErrorFrequency(int t, double maxPPF, double minPPF) {
        ArrayList<LRU> doneProcesses = new ArrayList<>();
        int errors = 0;
        int totalFramesUsed = 0;
        zatrzymania = 0;
        int remainingFrames = rozmiarPamieciFizycznej;

        //  Przydziel ramki procesom proporcjonalnie
        //  Pętla: (jeżeli lista procesów .isEmpty(), break)
        //  Dla procesów o WSS!=0, sprawdzam czy mam więcej wolnego miejsca niż ich WSS, jeżeli tak to przydzielam im WSS+1 ramek.
        //  Wykonuję aktywne procesy t razy, w sim.errors będą błędy z ostatnich t wykonań, bo zerowałem
        //  Usuwam procesy które skończyły się wykonywać.
        //  Obliczam PPF dla każdego procesu. Jeżeli PPF mniejszy od minPPF, to zabieram ramkę, jest ona wolna.
        //  Jeżeli jakiś proces ma PPF większy od maxPPF, to dodaję mu ramkę.
        //  Jeżeli nie ma ramek wolnych wstrzymuję proces, zapisuję ile miał ramek jako WSS. (Procesy aktywne mają WSS=0)
        //  Dodaję ilość błędów każdego procesu do sumy, i zeruję ilość błędów procesu.
        //  goto Pętla

        //Początkowy przydział proporcjonalny:

        for (LRU sim : simList) {
            totalFramesUsed += sim.range;
        }

        for (LRU sim : simList) {
            double percentage = (sim.range/totalFramesUsed*100);
            int assignedFrames = Math.max((int) (rozmiarPamieciFizycznej/100*percentage),1);
            sim.physicalMemorySize = assignedFrames;
            remainingFrames -= assignedFrames;
        }
        // Przydzielam ewentualne pozostałe ramki
        while (remainingFrames > 0) {
            for (LRU sim : simList) {
                if (remainingFrames == 0) break;
                sim.physicalMemorySize++;
                remainingFrames--;

            }
        }
        // Koniec przydziału proporcjonalnego

        while(!simList.isEmpty()) {

            for (LRU sim: simList) {
                // Jako WSS zapisuję ile ramek potrzebował wstrzymany proces.
                // Dla procesów o WSS!=0, sprawdzam czy mam więcej wolnego miejsca niż ich WSS, jeżeli tak to przydzielam im WSS+1 ramek.
                if (sim.WSS != 0 && sim.WSS < remainingFrames) {
                    sim.setMemorySize(sim.WSS+1); // WSS używam do przechowywania ilości ramek, które proces potrzebuje, bądź informacji że jest aktywny.
                    sim.WSS = 0; // Wznawiam proces
                    //System.out.println("Wznawiam proces");
                }
            }

            for (LRU sim: simList) {
                //t razy wykonuję requesty, sprawdzając czy current request nie jest na końcu zakresu.
                if (sim.WSS != 0) continue; // Jeżeli proces jest wstrzymany, nie wykonuję jego requestów
                for (int j = 0; j < t; j++) {
                    sim.doLRU();
                    if (sim.currentRequestIndex >= liczbaRequestow-1) {
                        // Jeżeli jakiś proces skończył wykonywać wszystkie swoje requesty, oznaczam go jako wykonany.
                        doneProcesses.add(sim);
                        break;
                    }
                }
            }
            doneProcesses.forEach(simList::remove); // Usuwam wykonane procesy z głównej listy.

            for(LRU sim: simList) {
                // Obliczam PPF dla każdego procesu. Jeżeli PPF mniejszy od minPPF, to zabieram ramkę, jest ona wolna.
                double PPF = (double) sim.errors / t;
                if (PPF < minPPF) {
                    sim.setMemorySize(sim.physicalMemorySize - 1);
                    remainingFrames++;
                }
            }

            for(LRU sim: simList) {
                // Jeżeli jakiś proces ma PPF większy od maxPPF, to dodaję mu ramkę.
                double PPF = (double) sim.errors / t;
                if (PPF > maxPPF && remainingFrames > 0) {
                    sim.setMemorySize(sim.physicalMemorySize + 1);
                    remainingFrames--;
                }
                else if (PPF > maxPPF) {
                    // Jeżeli nie ma ramek wolnych wstrzymuję proces, zapisuję ile miał ramek jako WSS i zwalniam jego ramki.
                    remainingFrames += sim.WSS = sim.physicalMemorySize;
                    sim.setMemorySize(0);
                    //System.out.println("Wstrzymuję proces");
                    zatrzymania++;
                }
            }

            for (LRU sim: simList) {
                // Dodaję ilość błędów każdego procesu do sumy, i zeruję ilość błędów procesu.
                errors += sim.errors;
                sim.errors = 0;
            }

        }


        return errors;
    }



}