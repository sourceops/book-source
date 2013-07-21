(ns jsa)

;; Set up prn and friends for debugging
(let [buf (array)]
  (set! *print-fn*
        (fn [s]
          (if-let [[_ pre post] (re-find #"(?m)^(.*)\n([^\n]*)" s)]
            (do
              (.push buf pre)
              (.log js/console (.join buf ""))
              (.splice buf 0 (.-length buf) post))
            (.push buf s)))))

(defn soft-attack [ctx {:keys [volume delay duration]}]
  (let [node (.createGainNode ctx)]    ;; createGain
    (doto (.-gain node)
      (.linearRampToValueAtTime 0 delay)
      (.linearRampToValueAtTime volume (+ delay 0.05))
      (.linearRampToValueAtTime 0 (+ delay duration)))
    node))

(defn sine-tone [ctx {:keys [cent delay duration]}]
  (let [node (.createOscillator ctx)]
    (set! (-> node .-frequency .-value) 440)
    (set! (-> node .-detune .-value) (- cent 900))
    (.noteOn node delay)
    (.noteOff node (+ delay duration))
    node))

(defn connect-to [node1 node2]
  (.connect node1 node2)
  node2)

(defn woo
  "Play a 'woo' sound; sounds a bit like a glass harp."
  [ctx note]
  (-> (sine-tone ctx note)
      (connect-to (soft-attack ctx note))))

(defn play!
  "Kick off playing a sequence of notes. note-fn must take two
  arguments, an AudioContext object and a map representing one note to
  play. It must return an AudioNode object that will play that note."
  [note-fn notes]
  (if-let [ctor (or (.-AudioContext js/window)
                      (.-webkitAudioContext js/window))]
    (let [ctx (new ctor)
          compressor (.createDynamicsCompressor ctx)] ;; for the safety of your speakers and ears
      (let [now (.-currentTime ctx)]
        (doseq [note notes]
          (->
           (note-fn ctx (update-in note [:delay] + now))
           (connect-to compressor))))
      (connect-to compressor (.-destination ctx)))
    (js/alert "Sorry, this browser doesn't seem to support AudioContext")))

(defn note-beats
  "Convert a sequence of numbers, pluses (for holds), and dashes (for
  rests) into a sequence of note objects used for playing and drawing."
  [melody]
  (reduce (fn [notes [beat x]]
            (cond
             (= '- x) notes
             (number? x) (conj notes {:delay-beats beat
                                      :note x :duration-beats 1})
             (= '+ x) (conj (pop notes)
                            (update-in (peek notes) [:duration-beats] inc))))
          []
          (map-indexed list melody)))

(defn time-scale
  "Compute time-based delay and duration for a sequence of notes,
  computed from the given number of Beats Per Minute."
  [notes bpm]
  (map (fn [note] (assoc note
                    :delay (* (/ 60 bpm) (:delay-beats note))
                    :duration (* (/ 60 bpm) (+ 1.3 (:duration-beats note)))))
       notes))

(defn build-scale
  "Return a function that converts a scale note number (such as given
  to note-beats) to a cent (offset into a chromatic scale) based on the
  given sequence of steps. The steps are numbers indicating how many
  chromatic scale steps it takes to get from one note to the next
  highest in the scale: 1 for a 'second', 2 for a 'third', etc."
  [steps]
  (let [steps-per-scale (apply + steps)]
    (fn [notes]
      (map #(assoc %
              :cent (+ (* 100 (apply + (take (Math/floor (:note %)) (cycle steps))))
                       (if (integer? (:note %))
                         0
                         100)))
           notes))))

(defn key-of
  "Adjusts the scale note number of a sequence of notes to put the
  notes into the given key. Assumes the input is in the key of C"
  [notes k]
  (let [offset ((zipmap [:C :D :E :F :G :A :B] (range)) k)]
    (map #(update-in % [:note] + offset) notes)))

(defn transpose
  "Adjusts the cent of a sequence of notes by n chromatic steps. Use
  after applying a scale to bring a tune into a given auditory range,
  or to get it to a more pleasant vertical position on the staff."
  [notes n]
  (map #(update-in % [:cent] + (* 100 n)) notes))

;; == staff ==

(def beats-per-staff 48)
(def beat-width 17)
(def staff-y-offset 100)

;; Compute a map of cents to [major-scale offset, sharp/flat] pairs.
;; Used to plot a given note onto the standard major G-clef staff.
(def cent-line
  (->> (cycle [2 2 1 2 2 2 1])
       (map-indexed list)
       (mapcat (fn [[i step]]
                 (take step [[i]
                             [i "<span class=\"sharp\">♯</span>"]
                             [(inc i) "<span class=\"flat\">♭</span>"]])))
       (zipmap (range 0 5000 100))))

(defn dset!
  "Like assoc-in for js objects."
  [base path value]
  (let [str-path (map str path)
        attr (last str-path)
        obj (reduce aget base (drop-last str-path))]
    (aset obj attr value)))

(defn px [x] (str x "px"))
(defn elem!
  "Create a DOM element and put it in the body."
  [tag]
  (let [elem (.createElement js.document tag)]
    (.appendChild (.-body js/document) elem)
    elem))

; rests: 𝄾 𝄽 𝄼 𝄻
(defn draw!
  "Draw the given note on the staff."
  [note]
  (let [char (["" "𝅘𝅥𝅮" "𝅘𝅥" "𝅘𝅥𝅭" "𝅗𝅥" "𝅗𝅥𝆆𝅘𝅥𝅮" "𝅗𝅥𝅭" "𝅗𝅥𝆆𝅘𝅥𝅭" "𝅝" "𝅝𝆆𝅘𝅥𝅮" "𝅗𝅥𝅭𝆆𝅗𝅥"] (:duration-beats note))
        [line sf] (cent-line (:cent note))
        staff (inc (Math/floor (/ (:delay-beats note) beats-per-staff)))
        beat-in-staff (rem (:delay-beats note) beats-per-staff)]
    (assoc note :span
           (doto (elem! "span")
             (dset! '[className] "note")
             (dset! '[innerHTML] (str sf char))
             (dset! '[style top] (px (- (* staff staff-y-offset) -9 (* 5.5 line))))
             (dset! '[style left] (px (+ 70 (* beat-in-staff beat-width))))))))

(defn draw-staff!
  "Draw lines and a clef for the n-th staff on the page."
  [n]
  (doto (elem! "span")
    (dset! '[className] "note")
    (dset! '[innerHTML] "𝄞")
    (dset! '[style top] (px (- (* n staff-y-offset) 8)))
    (dset! '[style left] (px 10)))
  (doto (elem! "div")
    (dset! '[className] "staff")
    (dset! '[innerHTML] "<hr><hr><hr><hr><hr>")
    (dset! '[style top] (px (* n staff-y-offset)))
    (dset! '[style width] (px (+ (* beats-per-staff beat-width) 40)))))

(defn draw-all!
  "Draw all the staffs needed for a given sequence of notes."
  [notes]
  (let [staffs (Math/ceil (/ (apply max (map :delay-beats notes)) beats-per-staff))]
    (doseq [staff (range staffs)]
      (draw-staff! (inc staff)))
    (doall (map draw! notes))))

(defn animate!
  "Animating all the given notes, starting right now."
  [notes]
  (doseq [note notes]
    (js/setTimeout #(dset! (:span note) '[className] "note playing")
                   (- (* 1000 (:delay note)) -200))
    (js/setTimeout #(dset! (:span note) '[className] "note")
                   (- (* 1000 (+ (:delay note) (:duration note))) -200))))

;; == data ==
(def chromatic (build-scale [1]))
(def major (build-scale [2 2 1 2 2 2 1]))
(def minor (build-scale [2 1 2 2 1 2 2]))
(def blues (build-scale [3 2 1 1 3 2]))
(def pentatonic (build-scale [3 2 2 3 2]))

;; A Christmas tune
(def away
  (let [a '[7 + 7 + + 6 5 + 5 + 4 + 3 + 3 + 2 + 1 + 0 + + +]
        b '[0 + 0 + + 1 0 + 0 + 4 + 2 + 1 + 0 + 3 + 5 + + +]
        c '[0 + 6 + + 5 4 + 5 + 4 + 3 + 4 + 1 + 2 + 3 + + +]]
    (note-beats (concat a b a c a b a c))))

;; A little counterpoint I came up with
(def away-harmony
  (let [a '[- - 5 3 5 - - - 3 1 2 + - - 1 -1 0 - - - 5 3 1 3]
        b '[- - -2 + + - - - -2 + - - - - -1 + - - - - -2 + + +]
        c '[- - 4 2 4 - - - 3 1 2 + - - 2 + -1 + 0 + -2 + + +]]
    (note-beats (concat (repeat 96 '-) a b a c))))

;; A children's round
(def row-row-row
  (note-beats
   '[0 + + 0 + + 0 + 1 2 + +
     2 + 1 2 + 3 4 + + + + +
     7 7 7 4 4 4 2 2 2 0 0 0
     4 + 3 2 + 1 0 + +]))

;; A magical theme
(def magical
  (note-beats
   '[4 +, 7 + + 9 8.5 +, 7 + + + 11 +, 10 + + + + +, 8.5 + + + + +
     7 + + 9 8.5 +, 6 + + + 8 +, 4 + + + + +, + + + +
     4 +, 7 + + 9 8.5 +, 7 + + + 11 +, 13 + + + 12.5 +, 12 + + +
     9.5 +, 12 + + 11 10.5 +, 3.5 + + + 9 +, 7 + + + + +, + + + +]))

;; == audio main ==

#_(def notes
  (-> row-row-row
      (time-scale 300)
      (key-of :C)
      major
      (transpose 5)
      draw-all!))

#_(def notes
  (-> (concat away away-harmony)
      (time-scale 200)
      (key-of :G)
      major
      ;;(transpose -3)
      draw-all!))
      ;; Also works with: (key-of :B) minor (transpose -3)

(def notes
  (-> magical
      (time-scale 300)
      (key-of :E)
      major
      (->> (map #(vector (:cent %) (:duration-beats %))))
      prn
      ;;(transpose -6)
      ;;draw-all!
      ))


(defn pair-to-note [[tone duration]]
  {:cent (* 100 tone)
   :duration duration
   :delay-beats 0
   :volume 0.4})

(defn consecutive-notes [notes]
  (reductions (fn [{:keys [delay duration]} note]
                (assoc note
                  :delay (+ delay duration)))
              notes))

(def magical-theme
  (concat
   [[11 2] [16 3] [19 1] [18 2] [16 4] [23 2]]
   [[21 6] [18 6] [16 3] [19 1] [18 2] [14 4] [17 2] [11 10]]
   [[11 2] [16 3] [19 1] [18 2] [16 4] [23 2]]
   [[26 4] [25 2] [24 4] [20 2] [24 3] [23 1] [22 2] [10 4] [19 2] [16 10]]))

(def notes
  (->> magical-theme
       (map pair-to-note)
       consecutive-notes
       (map #(update-in % [:delay] / 6))
       (map #(update-in % [:duration] / 6))))

(defn ^:export go []
  (play! woo notes)
  (animate! notes))

;; Uncomment to begin playing on page load
;; (go)



