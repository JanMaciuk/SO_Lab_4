import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.ThreadLocalRandom;

public class Main {  //wyniki: https://docs.google.com/spreadsheets/d/1QiDLjMX_lORQf7Wva6eKuCVYIUOWK37q4Ch1MktIQm0
    static int liczbaRequestow = 10000; // na proces
    static int rozmiarPamieciWirtualnej = 500; // Maksymalna na proces
    static int rozmiarPamieciFizycznej = 100; // Łączna pamięć, dla wszystkich procesów
    static int iloscProcesow = 5;
    private static LRU maxWSSProcess;
    private static LRU minPausedProcess;
    static ArrayList<LRU> simList = new ArrayList<>();
    public static void main(String[] args) {

        generateProcesses();

        System.out.println(runEqual());
        simList.forEach(LRU::reset); // Resetuje wszystkie statystyki symulacji przed kolejnym uruchomieniem
        System.out.println(runProportional());
        simList.forEach(LRU::reset); // Resetuje wszystkie statystyki symulacji przed kolejnym uruchomieniem
        System.out.println(runZoning(30)); //TODO: przydział ramek proporcjonalny.
        simList.forEach(LRU::reset); // Resetuje wszystkie statystyki symulacji przed kolejnym uruchomieniem


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
        final int[] errors = new int[1]; // lambda wymaga stałych, więc używam stałej tablicy ze zmiennym elementem ;)
        int c = t/2;
        // Początkowo ramki są rozdzielone równo pomiędzy procesami, nie mogę obliczyć WSS procesu zanim ten zacznie się wykonywać.
        // Pierwsze t iteracji: dodaje requesty do HashSet WorkingSet. (HashSet bo nie interesują mnie powtórki), rozmiar HashSet to WSS
        // pętla: (jeżeli lista procesów .isEmpty(), break)
        // Jeżeli mam miejsce wznawiam proces.
        // While (suma WSS-ów > rozmiar pamięci fizycznej) Ustawiam WSS procesu o największym WSS na 0 (wstrzymuje)
        // Następnie zwiększam WSS niewstrzymanych po kolei o 1, aż wykorzystam wszystkie ramki.
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
            }
        }

        while (!simList.isEmpty()) {

            while (freeFrames() < 0) {
                // Jeżeli nie ma wystarczająco pamięci fizycznej wstrzymuję procesy o największym WSS
                maxWSSProcess.WSS = 0;
                System.out.println("Wstrzymuję proces");
            }

            // Niemożliwa jest sytacja, gdzie wznowiłbym dopiero co wstrzymany proces, nie mam na niego miejsca.
            totalWSS(); // Odświeżam wskaźnik na zatrzymany proces o najmniejszym WSS;
            while(minPausedProcess != null && minPausedProcess.workingSet.size() <= freeFrames()) {
                // jeżeli jest jakiś wstrzymany proces, sprawdzam czy mamy na niego miejsce, jeśli tak to go wznawiam.
                if (minPausedProcess == null) continue;
                System.out.println("Wznawiam proces");
                minPausedProcess.WSS = minPausedProcess.workingSet.size();
            }


            while (freeFrames() > 0) {
                // Przydzielam pozostałe ramki pomiędzy aktywne procesy, żeby nie marnować zasobów
                for (LRU sim : simList) {
                    if (sim.WSS != 0 && freeFrames() > 0) {
                        sim.WSS++;
                    }
                    //TODO: przydział ramek proporcjonalny.
                }
            }
            for (LRU sim : simList) {
                if (sim.WSS == 0) continue; // Jeżeli proces jest wstrzymany, ignoruję go.
                // Przydzielam procesom tyle ramek, ile wynosi ich WSS (WSS=0 oznacza proces wstrzymany)
                sim.physicalMemorySize = sim.WSS;
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



}