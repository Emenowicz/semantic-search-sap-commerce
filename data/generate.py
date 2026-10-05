"""Generates the B2B parts test catalog and its eval queries.

Every product comes from structured attributes (real standard designations and dimension tables, invented
brands), and its texts are written in EN/DE/FR/IT from templates. Every query's expected products are
computed from those attributes by a predicate, never listed by hand, and the predicate is written next
to the query.

    python3 data/generate.py      (from the repository root; deterministic, no network)

Writes data/parts.json (the source of truth), data/parts-catalog.impex and eval/queries.tsv.
"""
import json
import os

LANGS = ["en", "de", "fr", "it"]
CATALOG = "partsCatalog"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def num(value, lang):
    """2.5 in English, 2,5 in German, French and Italian."""
    text = f"{value:g}"
    return text if lang == "en" else text.replace(".", ",")


CATEGORIES = {
    "parts": ["Industrial parts", "Industriebedarf", "Fournitures industrielles", "Forniture industriali"],
    "bearings": ["Deep groove ball bearings", "Rillenkugellager", "Roulements à billes à gorge profonde", "Cuscinetti radiali a sfere"],
    "screws": ["Socket head cap screws", "Zylinderschrauben mit Innensechskant", "Vis à tête cylindrique à six pans creux", "Viti a testa cilindrica con esagono incassato"],
    "nuts": ["Hexagon nuts", "Sechskantmuttern", "Écrous hexagonaux", "Dadi esagonali"],
    "orings": ["O-rings", "O-Ringe", "Joints toriques", "Anelli O-ring"],
    "vbelts": ["Narrow V-belts", "Schmalkeilriemen", "Courroies trapézoïdales étroites", "Cinghie trapezoidali strette"],
    "contactors": ["Contactors", "Schütze", "Contacteurs", "Contattori"],
    "mpcb": ["Motor protection circuit breakers", "Motorschutzschalter", "Disjoncteurs moteur", "Salvamotori"],
    "fittings": ["Push-in fittings", "Steckverschraubungen", "Raccords instantanés", "Raccordi istantanei"],
    "glands": ["Cable glands", "Kabelverschraubungen", "Presse-étoupes", "Pressacavi"],
    "greases": ["Lubricating greases", "Schmierfette", "Graisses lubrifiantes", "Grassi lubrificanti"],
}

products = []


def add(code, category, brand, attrs, names, descriptions):
    products.append({"code": code, "category": category, "brand": brand, "attrs": attrs,
                     "name": dict(zip(LANGS, names)), "description": dict(zip(LANGS, descriptions))})


# --- deep groove ball bearings: ISO dimension tables, two brands with identical designations -------------
BEARINGS = {  # designation: (bore d, outer D, width B) in mm
    "6000": (10, 26, 8), "6001": (12, 28, 8), "6002": (15, 32, 9), "6003": (17, 35, 10), "6004": (20, 42, 12), "6005": (25, 47, 12),
    "6200": (10, 30, 9), "6201": (12, 32, 10), "6202": (15, 35, 11), "6203": (17, 40, 12), "6204": (20, 47, 14), "6205": (25, 52, 15), "6206": (30, 62, 16),
    "6300": (10, 35, 11), "6301": (12, 37, 12), "6302": (15, 42, 13), "6303": (17, 47, 14), "6304": (20, 52, 15),
}
SEALS = {
    "": ["open, unsealed, for oil or grease lubrication by the machine",
         "offen, ohne Dichtung, für Öl- oder Fettschmierung durch die Maschine",
         "ouvert, sans étanchéité, pour lubrification à l'huile ou à la graisse par la machine",
         "aperto, senza tenuta, per lubrificazione a olio o grasso dalla macchina"],
    "ZZ": ["shielded on both sides with metal shields (ZZ), greased for life, for higher speeds",
           "beidseitig mit Metalldeckscheiben (ZZ), lebensdauergeschmiert, für höhere Drehzahlen",
           "flasqué des deux côtés (ZZ), graissé à vie, pour vitesses élevées",
           "con schermi metallici su entrambi i lati (ZZ), lubrificato a vita, per velocità elevate"],
    "2RS": ["sealed on both sides with rubber seals (2RS), greased for life, protected against dust and splash water",
            "beidseitig mit Gummidichtungen abgedichtet (2RS), lebensdauergeschmiert, geschützt gegen Staub und Spritzwasser",
            "étanche des deux côtés par joints caoutchouc (2RS), graissé à vie, protégé contre la poussière et les projections d'eau",
            "a tenuta su entrambi i lati con guarnizioni in gomma (2RS), lubrificato a vita, protetto da polvere e spruzzi d'acqua"],
}
for brand, prefix in (("Kugelwerk", "KW"), ("Rolltech", "RT")):
    for designation, (d, outer, width) in BEARINGS.items():
        for seal, seal_text in SEALS.items():
            full = designation + (f"-{seal}" if seal else "")
            dims = f"{d} x {outer} x {width} mm"
            add(f"{prefix}-{full}", "bearings", brand,
                {"designation": designation, "seal": seal, "d": d, "D": outer, "B": width},
                [f"{brand} deep groove ball bearing {full}, {dims}", f"{brand} Rillenkugellager {full}, {dims}",
                 f"{brand} roulement à billes {full}, {dims}", f"{brand} cuscinetto a sfere {full}, {dims}"],
                [f"Bore {d} mm, outer diameter {outer} mm, width {width} mm. {seal_text[0].capitalize()}. Bearing steel 100Cr6.",
                 f"Bohrung {d} mm, Außendurchmesser {outer} mm, Breite {width} mm. {seal_text[1].capitalize()}. Wälzlagerstahl 100Cr6.",
                 f"Alésage {d} mm, diamètre extérieur {outer} mm, largeur {width} mm. {seal_text[2].capitalize()}. Acier à roulements 100Cr6.",
                 f"Foro {d} mm, diametro esterno {outer} mm, larghezza {width} mm. {seal_text[3].capitalize()}. Acciaio per cuscinetti 100Cr6."])

# --- fasteners: DIN 912 socket head cap screws, DIN 934 hexagon nuts ----------------------------------------
MATERIALS = {  # name in product names, description text
    "8.8": (["steel 8.8, zinc-plated", "Stahl 8.8, verzinkt", "acier 8.8, zingué", "acciaio 8.8, zincato"],
            ["Steel 8.8, zinc-plated", "Stahl 8.8, verzinkt", "Acier 8.8, zingué", "Acciaio 8.8, zincato"]),
    "A2": (["stainless A2", "Edelstahl A2", "inox A2", "inox A2"],
           ["Stainless steel A2 (1.4301)", "Edelstahl A2 (1.4301)", "Acier inoxydable A2 (1.4301)", "Acciaio inox A2 (1.4301)"]),
    "A4": (["stainless A4", "Edelstahl A4", "inox A4", "inox A4"],
           ["Stainless steel A4 (1.4401), acid-resistant, for marine and chemical environments",
            "Edelstahl A4 (1.4401), säurebeständig, für Meerwasser und Chemie",
            "Acier inoxydable A4 (1.4401), résistant aux acides, pour milieu marin et chimique",
            "Acciaio inox A4 (1.4401), resistente agli acidi, per ambienti marini e chimici"]),
}
SCREW_LENGTHS = {4: [10, 16, 20], 5: [10, 16, 20, 25], 6: [16, 20, 25, 30, 40], 8: [20, 25, 30, 35, 40, 50],
                 10: [25, 30, 40, 50, 60], 12: [30, 40, 50, 60]}
for m, lengths in SCREW_LENGTHS.items():
    pack = 100 if m <= 6 else 50 if m <= 10 else 25
    for length in lengths:
        for material, (short, long) in MATERIALS.items():
            size = f"M{m} x {length}"
            add(f"FX-912-M{m}x{length}-{material}", "screws", "Fixtec",
                {"m": m, "length": length, "material": material, "pack": pack},
                [f"Fixtec socket head cap screw DIN 912 {size}, {short[0]}, pack of {pack}",
                 f"Fixtec Zylinderschraube mit Innensechskant DIN 912 {size}, {short[1]}, {pack} Stück",
                 f"Fixtec vis à tête cylindrique six pans creux DIN 912 {size}, {short[2]}, boîte de {pack}",
                 f"Fixtec vite a testa cilindrica con esagono incassato DIN 912 {size}, {short[3]}, conf. {pack}"],
                [f"ISO 4762 / DIN 912, metric thread M{m}, length {length} mm, hexagon socket drive. Material: {long[0]}.",
                 f"ISO 4762 / DIN 912, metrisches Gewinde M{m}, Länge {length} mm, Antrieb Innensechskant. Werkstoff: {long[1]}.",
                 f"ISO 4762 / DIN 912, filetage métrique M{m}, longueur {length} mm, empreinte six pans creux. Matière : {long[2]}.",
                 f"ISO 4762 / DIN 912, filettatura metrica M{m}, lunghezza {length} mm, impronta esagonale incassata. Materiale: {long[3]}."])
NUT_WIDTHS = {4: 7, 5: 8, 6: 10, 8: 13, 10: 17, 12: 19, 16: 24}  # DIN 934 width across flats, mm
for m, across in NUT_WIDTHS.items():
    for material, (short, long) in MATERIALS.items():
        add(f"FX-934-M{m}-{material}", "nuts", "Fixtec", {"m": m, "material": material, "pack": 100},
            [f"Fixtec hexagon nut DIN 934 M{m}, {short[0]}, pack of 100", f"Fixtec Sechskantmutter DIN 934 M{m}, {short[1]}, 100 Stück",
             f"Fixtec écrou hexagonal DIN 934 M{m}, {short[2]}, boîte de 100", f"Fixtec dado esagonale DIN 934 M{m}, {short[3]}, conf. 100"],
            [f"ISO 4032 / DIN 934, metric thread M{m}, width across flats {across} mm. Material: {long[0]}.",
             f"ISO 4032 / DIN 934, metrisches Gewinde M{m}, Schlüsselweite {across} mm. Werkstoff: {long[1]}.",
             f"ISO 4032 / DIN 934, filetage métrique M{m}, surplat {across} mm. Matière : {long[2]}.",
             f"ISO 4032 / DIN 934, filettatura metrica M{m}, chiave {across} mm. Materiale: {long[3]}."])

# --- O-rings: size x material, with each material's temperature range and media --------------------------
ELASTOMERS = {
    "NBR": (70, -30, 100, ["Nitrile rubber NBR, 70 Shore A. Resistant to mineral oils, greases and fuels.",
                           "Nitrilkautschuk NBR, 70 Shore A. Beständig gegen Mineralöle, Fette und Kraftstoffe.",
                           "Caoutchouc nitrile NBR, 70 Shore A. Résiste aux huiles minérales, graisses et carburants.",
                           "Gomma nitrilica NBR, 70 Shore A. Resistente a oli minerali, grassi e carburanti."]),
    "FKM": (80, -20, 200, ["Fluoroelastomer FKM, 80 Shore A. For high temperatures and aggressive media, oils and many chemicals.",
                           "Fluorkautschuk FKM, 80 Shore A. Für hohe Temperaturen und aggressive Medien, Öle und viele Chemikalien.",
                           "Élastomère fluoré FKM, 80 Shore A. Pour hautes températures et fluides agressifs, huiles et nombreux produits chimiques.",
                           "Fluoroelastomero FKM, 80 Shore A. Per alte temperature e fluidi aggressivi, oli e molti prodotti chimici."]),
    "EPDM": (70, -40, 150, ["Ethylene propylene EPDM, 70 Shore A. For water, steam and outdoor use; not suitable for mineral oil.",
                            "Ethylen-Propylen EPDM, 70 Shore A. Für Wasser, Dampf und den Außeneinsatz; nicht für Mineralöl geeignet.",
                            "Éthylène-propylène EPDM, 70 Shore A. Pour l'eau, la vapeur et l'extérieur ; ne convient pas aux huiles minérales.",
                            "Etilene-propilene EPDM, 70 Shore A. Per acqua, vapore e uso esterno; non adatto all'olio minerale."]),
}
ORING_SIZES = [(5, 1.5), (8, 2), (10, 2), (12, 2), (15, 2.5), (20, 2.5), (25, 3), (30, 3), (40, 3.5), (50, 3.5)]
for inner, section in ORING_SIZES:
    for material, (shore, t_min, t_max, text) in ELASTOMERS.items():
        size = {lang: f"{num(inner, lang)} x {num(section, lang)} mm" for lang in LANGS}
        temps = [f"Temperature range {t_min} to +{t_max} °C.", f"Temperaturbereich {t_min} bis +{t_max} °C.",
                 f"Plage de température {t_min} à +{t_max} °C.", f"Temperatura da {t_min} a +{t_max} °C."]
        add(f"SM-OR-{num(inner, 'en')}x{num(section, 'en')}-{material}", "orings", "Sealmax",
            {"id": inner, "cs": section, "material": material, "t_min": t_min, "t_max": t_max},
            [f"Sealmax O-ring {size['en']} {material} {shore}, pack of 25", f"Sealmax O-Ring {size['de']} {material} {shore}, 25 Stück",
             f"Sealmax joint torique {size['fr']} {material} {shore}, sachet de 25", f"Sealmax O-ring {size['it']} {material} {shore}, conf. 25"],
            [f"Inner diameter {num(inner, 'en')} mm, cross-section {num(section, 'en')} mm. {text[0]} {temps[0]}",
             f"Innendurchmesser {num(inner, 'de')} mm, Schnurstärke {num(section, 'de')} mm. {text[1]} {temps[1]}",
             f"Diamètre intérieur {num(inner, 'fr')} mm, section {num(section, 'fr')} mm. {text[2]} {temps[2]}",
             f"Diametro interno {num(inner, 'it')} mm, sezione {num(section, 'it')} mm. {text[3]} {temps[3]}"])

# --- narrow V-belts: profile x datum length ----------------------------------------------------------------
BELTS = {"SPZ": (9.7, False, [1000, 1250, 1500, 1800, 2000]), "SPA": (12.7, False, [1000, 1250, 1500, 1800, 2000]),
         "XPZ": (9.7, True, [1000, 1250, 1500])}
for profile, (top, cogged, lengths) in BELTS.items():
    for length in lengths:
        kind = (["Cogged narrow V-belt", "Gezahnter Schmalkeilriemen", "Courroie trapézoïdale étroite crantée", "Cinghia trapezoidale stretta dentata"]
                if cogged else ["Narrow V-belt", "Schmalkeilriemen", "Courroie trapézoïdale étroite", "Cinghia trapezoidale stretta"])
        extra = (["Cogged for small pulleys and better heat dissipation.", "Gezahnt für kleine Scheiben und bessere Wärmeabfuhr.",
                  "Crantée pour petites poulies et meilleure dissipation de la chaleur.", "Dentata per pulegge piccole e migliore dissipazione del calore."]
                 if cogged else ["", "", "", ""])
        add(f"BL-{profile}-{length}", "vbelts", "Belton", {"profile": profile, "length": length, "cogged": cogged},
            [f"Belton {kind[i]} {profile} {length}, {num(top, lang)} mm" for i, lang in enumerate(LANGS)],
            [(f"Profile {profile} to DIN 7753 / ISO 4184, datum length {length} mm, top width {num(top, 'en')} mm. Oil- and heat-resistant, antistatic. {extra[0]}").strip(),
             (f"Profil {profile} nach DIN 7753 / ISO 4184, Wirklänge {length} mm, obere Breite {num(top, 'de')} mm. Öl- und hitzebeständig, antistatisch. {extra[1]}").strip(),
             (f"Profil {profile} selon DIN 7753 / ISO 4184, longueur primitive {length} mm, largeur au sommet {num(top, 'fr')} mm. Résistante à l'huile et à la chaleur, antistatique. {extra[2]}").strip(),
             (f"Profilo {profile} secondo DIN 7753 / ISO 4184, lunghezza primitiva {length} mm, larghezza superiore {num(top, 'it')} mm. Resistente a olio e calore, antistatica. {extra[3]}").strip()])

# --- contactors and motor protection circuit breakers: two brands --------------------------------------------
CONTACTORS = [(4, 9), (5.5, 12), (7.5, 18), (11, 25), (15, 32), (18.5, 38)]  # AC-3 kW at 400 V, rated current A
COILS = {"24DC": ["24 V DC", "24 V DC", "24 V CC", "24 V CC"], "230AC": ["230 V AC", "230 V AC", "230 V CA", "230 V CA"]}
MPCB_RANGES = [(0.63, 1, 0.25), (1, 1.6, 0.55), (1.6, 2.5, 0.75), (2.5, 4, 1.5), (4, 6.3, 2.2), (6, 10, 4),
               (9, 14, 5.5), (13, 18, 7.5), (17, 23, 11), (20, 25, 11)]  # setting range A, motor up to kW at 400 V
for brand, prefix in (("Volteq", "VQ"), ("Elmatic", "EM")):
    for kw, amps in CONTACTORS:
        for coil, coil_text in COILS.items():
            add(f"{prefix}-C{amps}-{coil}", "contactors", brand, {"kw": kw, "amps": amps, "coil": coil},
                [f"{brand} contactor 3-pole {num(kw, 'en')} kW / {amps} A, coil {coil_text[0]}",
                 f"{brand} Leistungsschütz 3-polig {num(kw, 'de')} kW / {amps} A, Spule {coil_text[1]}",
                 f"{brand} contacteur tripolaire {num(kw, 'fr')} kW / {amps} A, bobine {coil_text[2]}",
                 f"{brand} contattore tripolare {num(kw, 'it')} kW / {amps} A, bobina {coil_text[3]}"],
                [f"AC-3 rating {num(kw, 'en')} kW at 400 V, rated operational current {amps} A. Coil voltage {coil_text[0]}. 1 auxiliary contact NO. DIN rail or screw mounting.",
                 f"Bemessungsleistung AC-3 {num(kw, 'de')} kW bei 400 V, Bemessungsbetriebsstrom {amps} A. Spulenspannung {coil_text[1]}. 1 Hilfskontakt Schließer. Hutschienen- oder Schraubbefestigung.",
                 f"Puissance AC-3 {num(kw, 'fr')} kW sous 400 V, courant assigné d'emploi {amps} A. Tension de bobine {coil_text[2]}. 1 contact auxiliaire NO. Montage sur rail DIN ou par vis.",
                 f"Potenza AC-3 {num(kw, 'it')} kW a 400 V, corrente nominale d'impiego {amps} A. Tensione bobina {coil_text[3]}. 1 contatto ausiliario NA. Montaggio su guida DIN o a vite."])
    for low, high, motor_kw in MPCB_RANGES:
        rng = {lang: f"{num(low, lang)}–{num(high, lang)} A" for lang in LANGS}
        add(f"{prefix}-MS-{num(low, 'en')}-{num(high, 'en')}", "mpcb", brand, {"low": low, "high": high, "motor_kw": motor_kw},
            [f"{brand} motor protection circuit breaker {rng['en']}", f"{brand} Motorschutzschalter {rng['de']}",
             f"{brand} disjoncteur moteur {rng['fr']}", f"{brand} interruttore salvamotore {rng['it']}"],
            [f"Adjustable thermal overload release {rng['en']}, magnetic short-circuit release. 3-pole, for motors up to {num(motor_kw, 'en')} kW at 400 V. Rotary handle, lockable.",
             f"Einstellbarer thermischer Überlastauslöser {rng['de']}, magnetischer Kurzschlussauslöser. 3-polig, für Motoren bis {num(motor_kw, 'de')} kW bei 400 V. Drehantrieb, abschließbar.",
             f"Déclencheur thermique réglable {rng['fr']}, déclencheur magnétique de court-circuit. Tripolaire, pour moteurs jusqu'à {num(motor_kw, 'fr')} kW sous 400 V. Commande rotative, cadenassable.",
             f"Sganciatore termico regolabile {rng['it']}, sganciatore magnetico di cortocircuito. Tripolare, per motori fino a {num(motor_kw, 'it')} kW a 400 V. Comando rotativo, lucchettabile."])

# --- pneumatic push-in fittings --------------------------------------------------------------------------------
THREADS = {4: ["G1/8"], 6: ["G1/8", "G1/4"], 8: ["G1/4", "G3/8"], 10: ["G1/4", "G3/8"], 12: ["G3/8"]}
FITTING_BASE = ["For compressed air, polyamide and polyurethane tube with outer diameter {od} mm. Nickel-plated brass and technopolymer, max. 10 bar. Release by pressing the collet.",
                "Für Druckluft, PA- und PU-Schläuche mit Außendurchmesser {od} mm. Messing vernickelt und Technopolymer, max. 10 bar. Lösen durch Drücken des Löserings.",
                "Pour air comprimé, tubes polyamide et polyuréthane de diamètre extérieur {od} mm. Laiton nickelé et technopolymère, max. 10 bar. Déverrouillage par pression sur la bague.",
                "Per aria compressa, tubi in poliammide e poliuretano con diametro esterno {od} mm. Ottone nichelato e tecnopolimero, max 10 bar. Sblocco premendo l'anello."]
THREAD_NOTE = [" Male thread {t} with sealing coating.", " Außengewinde {t} mit Dichtbeschichtung.",
               " Filetage mâle {t} avec revêtement d'étanchéité.", " Filetto maschio {t} con rivestimento sigillante."]
for od, threads in THREADS.items():
    for shape, prefix, shape_names in (("straight", "ST", ["straight", "gerade", "droit", "diritto"]),
                                       ("elbow", "EL", ["elbow", "Winkel", "coudé", "a gomito"])):
        for thread in threads:
            add(f"PF-{prefix}-{od}-{thread.replace('/', '')}", "fittings", "Pneumaflow", {"shape": shape, "od": od, "thread": thread},
                [f"Pneumaflow push-in fitting {shape_names[0]}, {od} mm tube, {thread} male thread",
                 f"Pneumaflow Steckverschraubung {shape_names[1]}, Schlauch {od} mm, Außengewinde {thread}",
                 f"Pneumaflow raccord instantané {shape_names[2]}, tube {od} mm, filetage mâle {thread}",
                 f"Pneumaflow raccordo istantaneo {shape_names[3]}, tubo {od} mm, filetto maschio {thread}"],
                [FITTING_BASE[i].format(od=od) + THREAD_NOTE[i].format(t=thread) for i in range(4)])
    for shape, prefix, names in (("tee", "T", ["push-in T-connector", "Steck-T-Verbinder", "raccord instantané en T", "raccordo istantaneo a T"]),
                                 ("union", "U", ["push-in straight union", "Steckverbinder gerade", "raccord instantané union droite", "raccordo istantaneo diritto di giunzione"])):
        add(f"PF-{prefix}-{od}", "fittings", "Pneumaflow", {"shape": shape, "od": od, "thread": None},
            [f"Pneumaflow {names[0]}, {od} mm tube", f"Pneumaflow {names[1]}, Schlauch {od} mm",
             f"Pneumaflow {names[2]}, tube {od} mm", f"Pneumaflow {names[3]}, tubo {od} mm"],
            [FITTING_BASE[i].format(od=od) for i in range(4)])

# --- cable glands: thread x material ---------------------------------------------------------------------------
GLANDS = {12: (3, 6.5), 16: (4, 8), 20: (6, 12), 25: (9, 17), 32: (13, 21)}  # metric thread: clamping range mm
GLAND_MATERIALS = {
    "PA": (["polyamide", "Polyamid", "polyamide", "poliammide"],
           ["Polyamide, grey RAL 7035, -40 to +100 °C.", "Polyamid, grau RAL 7035, -40 bis +100 °C.",
            "Polyamide, gris RAL 7035, -40 à +100 °C.", "Poliammide, grigio RAL 7035, da -40 a +100 °C."]),
    "MS": (["nickel-plated brass", "Messing vernickelt", "laiton nickelé", "ottone nichelato"],
           ["Nickel-plated brass, for higher mechanical loads, -40 to +100 °C.", "Messing vernickelt, für höhere mechanische Belastung, -40 bis +100 °C.",
            "Laiton nickelé, pour contraintes mécaniques élevées, -40 à +100 °C.", "Ottone nichelato, per sollecitazioni meccaniche elevate, da -40 a +100 °C."]),
}
for m, (low, high) in GLANDS.items():
    for material, (name, text) in GLAND_MATERIALS.items():
        rng = {lang: f"{num(low, lang)}–{num(high, lang)} mm" for lang in LANGS}
        add(f"GX-M{m}-{material}", "glands", "Glandex", {"m": m, "low": low, "high": high, "material": material},
            [f"Glandex cable gland M{m} x 1.5, {name[0]}, for cable {rng['en']}", f"Glandex Kabelverschraubung M{m} x 1,5, {name[1]}, für Kabel {rng['de']}",
             f"Glandex presse-étoupe M{m} x 1,5, {name[2]}, pour câble {rng['fr']}", f"Glandex pressacavo M{m} x 1,5, {name[3]}, per cavo {rng['it']}"],
            [f"Metric thread M{m} x 1.5, clamping range {rng['en']}, protection IP68. {text[0]}",
             f"Metrisches Gewinde M{m} x 1,5, Klemmbereich {rng['de']}, Schutzart IP68. {text[1]}",
             f"Filetage métrique M{m} x 1,5, plage de serrage {rng['fr']}, protection IP68. {text[2]}",
             f"Filettatura metrica M{m} x 1,5, campo di serraggio {rng['it']}, protezione IP68. {text[3]}"])

# --- lubricating greases ---------------------------------------------------------------------------------------
GREASES = [
    ("EP2", 400, "multipurpose", ["multipurpose grease EP2, 400 g cartridge", "Mehrzweckfett EP2, Kartusche 400 g", "graisse multi-usages EP2, cartouche 400 g", "grasso multiuso EP2, cartuccia 400 g"],
     ["Lithium soap grease, NLGI 2, with extreme-pressure additives. For plain and rolling bearings, -30 to +120 °C.",
      "Lithiumverseiftes Fett, NLGI 2, mit Hochdruckzusätzen. Für Gleit- und Wälzlager, -30 bis +120 °C.",
      "Graisse au savon de lithium, NLGI 2, avec additifs extrême pression. Pour paliers lisses et roulements, -30 à +120 °C.",
      "Grasso al sapone di litio, NLGI 2, con additivi estreme pressioni. Per cuscinetti a strisciamento e volventi, da -30 a +120 °C."]),
    ("EP2", 1000, "multipurpose", ["multipurpose grease EP2, 1 kg can", "Mehrzweckfett EP2, Dose 1 kg", "graisse multi-usages EP2, boîte 1 kg", "grasso multiuso EP2, barattolo 1 kg"],
     None),
    ("H1", 400, "food", ["food-grade grease NSF H1, 400 g cartridge", "lebensmitteltaugliches Fett NSF H1, Kartusche 400 g", "graisse alimentaire NSF H1, cartouche 400 g", "grasso alimentare NSF H1, cartuccia 400 g"],
     ["Aluminium complex grease registered NSF H1 for incidental food contact. For food, beverage and packaging machines, -20 to +140 °C.",
      "Aluminiumkomplexfett, NSF-H1-registriert für gelegentlichen Lebensmittelkontakt. Für Lebensmittel-, Getränke- und Verpackungsmaschinen, -20 bis +140 °C.",
      "Graisse à complexe d'aluminium homologuée NSF H1 pour contact alimentaire fortuit. Pour machines alimentaires, de boissons et d'emballage, -20 à +140 °C.",
      "Grasso al complesso di alluminio registrato NSF H1 per contatto accidentale con alimenti. Per macchine alimentari, per bevande e confezionamento, da -20 a +140 °C."]),
    ("H1", 1000, "food", ["food-grade grease NSF H1, 1 kg can", "lebensmitteltaugliches Fett NSF H1, Dose 1 kg", "graisse alimentaire NSF H1, boîte 1 kg", "grasso alimentare NSF H1, barattolo 1 kg"],
     None),
    ("HT", 400, "high-temperature", ["high-temperature grease, 400 g cartridge", "Hochtemperaturfett, Kartusche 400 g", "graisse haute température, cartouche 400 g", "grasso per alte temperature, cartuccia 400 g"],
     ["Polyurea grease, NLGI 2, for bearings running hot, e.g. in kilns, dryers and fans, -20 to +200 °C.",
      "Polyharnstofffett, NLGI 2, für heiß laufende Lager, z. B. in Öfen, Trocknern und Lüftern, -20 bis +200 °C.",
      "Graisse polyurée, NLGI 2, pour roulements chauds, p. ex. fours, séchoirs et ventilateurs, -20 à +200 °C.",
      "Grasso alla poliurea, NLGI 2, per cuscinetti caldi, ad es. in forni, essiccatori e ventilatori, da -20 a +200 °C."]),
    ("EM", 400, "electric-motor", ["electric motor bearing grease, 400 g cartridge", "Elektromotoren-Lagerfett, Kartusche 400 g", "graisse pour roulements de moteurs électriques, cartouche 400 g", "grasso per cuscinetti di motori elettrici, cartuccia 400 g"],
     ["Lithium-calcium grease, NLGI 2-3, low noise and long life at high speeds. For electric motor and fan bearings, -30 to +140 °C.",
      "Lithium-Calcium-Fett, NLGI 2-3, geräuscharm und langlebig bei hohen Drehzahlen. Für Lager von Elektromotoren und Lüftern, -30 bis +140 °C.",
      "Graisse lithium-calcium, NLGI 2-3, silencieuse et longue durée à grande vitesse. Pour roulements de moteurs électriques et ventilateurs, -30 à +140 °C.",
      "Grasso litio-calcio, NLGI 2-3, silenzioso e di lunga durata ad alte velocità. Per cuscinetti di motori elettrici e ventilatori, da -30 a +140 °C."]),
]
grease_texts = {}
for code, grams, use, names, texts in GREASES:
    texts = texts or grease_texts[code]
    grease_texts[code] = texts
    add(f"LB-{code}-{grams}", "greases", "Lubrion", {"type": code, "grams": grams, "use": use},
        [f"Lubrion {name}" for name in names], texts)

# --- queries: each expected set is computed from attributes ----------------------------------------------------
queries = []


def query(lang, kind, text, predicate_text, predicate):
    expected = [p["code"] for p in products if predicate(p["category"], p["attrs"])]
    queries.append({"id": f"q{len(queries) + 1:03d}", "lang": lang, "kind": kind, "query": text,
                    "expected": "|".join(expected), "predicate": predicate_text})


def bearing(designation=None, seal=None, d=None, outer=None):
    return lambda c, a: (c == "bearings" and (designation is None or a["designation"] == designation)
                         and (seal is None or a["seal"] in seal) and (d is None or a["d"] == d) and (outer is None or a["D"] == outer))


def screw(m, length=None, materials=None):
    return lambda c, a: c == "screws" and a["m"] == m and (length is None or a["length"] == length) and (materials is None or a["material"] in materials)


def oring(inner=None, section=None, materials=None):
    return lambda c, a: (c == "orings" and (inner is None or a["id"] == inner) and (section is None or a["cs"] == section)
                         and (materials is None or a["material"] in materials))


# codes and designations: what a buyer copies from the old part or the drawing
query("en", "code", "6204-2RS", "bearing 6204, seal 2RS", bearing("6204", ["2RS"]))
query("de", "code", "6205 2RS", "bearing 6205, seal 2RS", bearing("6205", ["2RS"]))
query("fr", "code", "6204 ZZ", "bearing 6204, seal ZZ", bearing("6204", ["ZZ"]))
query("it", "code", "6302", "bearing 6302, any seal", bearing("6302"))
query("en", "code", "DIN 912 M8x30 A2", "screw M8 x 30, A2", screw(8, 30, ["A2"]))
query("de", "code", "M10x40 A4 Zylinderschraube", "screw M10 x 40, A4", screw(10, 40, ["A4"]))
query("de", "code", "DIN 934 M12 verzinkt", "nut M12, 8.8 zinc-plated", lambda c, a: c == "nuts" and a["m"] == 12 and a["material"] == "8.8")
query("en", "code", "SPZ 1250", "belt SPZ 1250", lambda c, a: c == "vbelts" and a["profile"] == "SPZ" and a["length"] == 1250)
query("it", "code", "XPZ 1000", "belt XPZ 1000", lambda c, a: c == "vbelts" and a["profile"] == "XPZ" and a["length"] == 1000)
query("fr", "code", "OR 30x3 NBR", "O-ring 30 x 3, NBR", oring(30, 3, ["NBR"]))
query("de", "code", "G1/4 8 mm Steckverschraubung", "fitting, 8 mm tube, G1/4", lambda c, a: c == "fittings" and a["od"] == 8 and a["thread"] == "G1/4")
query("en", "code", "M20 cable gland brass", "gland M20, brass", lambda c, a: c == "glands" and a["m"] == 20 and a["material"] == "MS")

# trade words that never appear in product texts (DE Inbus, FR CHC, IT brugola, EN Allen) and plain-language needs
query("de", "synonym", "Inbusschraube M6 x 20 Edelstahl", "screw M6 x 20, stainless (A2 or A4)", screw(6, 20, ["A2", "A4"]))
query("fr", "synonym", "vis CHC M8 x 40 inox A4", "screw M8 x 40, A4", screw(8, 40, ["A4"]))
query("it", "synonym", "brugola M5 x 16", "screw M5 x 16, any material", screw(5, 16))
query("en", "synonym", "Allen bolt M12 x 50 zinc plated", "screw M12 x 50, 8.8 zinc-plated", screw(12, 50, ["8.8"]))
query("de", "synonym", "Imbusschrauben M4 x 10 rostfrei", "screw M4 x 10, stainless (A2 or A4)", screw(4, 10, ["A2", "A4"]))
query("it", "synonym", "bullone a brugola M10 x 30 acciaio zincato", "screw M10 x 30, 8.8 zinc-plated", screw(10, 30, ["8.8"]))
query("de", "synonym", "Kugellager 20 mm Welle beidseitig abgedichtet", "bearing, bore 20, seal 2RS", bearing(seal=["2RS"], d=20))
query("en", "synonym", "sealed bearing 25 mm bore", "bearing, bore 25, seal 2RS", bearing(seal=["2RS"], d=25))
query("fr", "synonym", "roulement étanche 17x40", "bearing 17 x 40, seal 2RS", bearing(seal=["2RS"], d=17, outer=40))
query("it", "synonym", "cuscinetto schermato 6203", "bearing 6203, seal ZZ", bearing("6203", ["ZZ"]))
query("de", "synonym", "Druckluft Winkelstecker 6 mm", "fitting elbow, 6 mm tube", lambda c, a: c == "fittings" and a["shape"] == "elbow" and a["od"] == 6)
query("fr", "synonym", "coude pneumatique 10 mm G3/8", "fitting elbow, 10 mm, G3/8", lambda c, a: c == "fittings" and a["shape"] == "elbow" and a["od"] == 10 and a["thread"] == "G3/8")
query("it", "synonym", "raccordo a T aria compressa 12 mm", "fitting tee, 12 mm", lambda c, a: c == "fittings" and a["shape"] == "tee" and a["od"] == 12)
query("en", "synonym", "air hose tee 4 mm", "fitting tee, 4 mm", lambda c, a: c == "fittings" and a["shape"] == "tee" and a["od"] == 4)
query("de", "synonym", "Keilriemen SPA 1800", "belt SPA 1800", lambda c, a: c == "vbelts" and a["profile"] == "SPA" and a["length"] == 1800)
query("fr", "synonym", "courroie crantée XPZ 1500", "belt XPZ 1500", lambda c, a: c == "vbelts" and a["profile"] == "XPZ" and a["length"] == 1500)
query("de", "synonym", "Mutter M8 rostfrei säurebeständig", "nut M8, A4", lambda c, a: c == "nuts" and a["m"] == 8 and a["material"] == "A4")
query("en", "synonym", "screw M6 x 25 for seawater", "screw M6 x 25, A4", screw(6, 25, ["A4"]))

# numbers that need comparing, not matching: ranges, minimum ratings, temperatures
query("de", "constraint", "Motorschutzschalter für 3 A Motorstrom", "MPCB range contains 3 A", lambda c, a: c == "mpcb" and a["low"] <= 3 <= a["high"])
query("en", "constraint", "motor protection breaker for a 7 A motor", "MPCB range contains 7 A", lambda c, a: c == "mpcb" and a["low"] <= 7 <= a["high"])
query("fr", "constraint", "disjoncteur moteur 12 A", "MPCB range contains 12 A", lambda c, a: c == "mpcb" and a["low"] <= 12 <= a["high"])
query("it", "constraint", "salvamotore per motore da 20 A", "MPCB range contains 20 A", lambda c, a: c == "mpcb" and a["low"] <= 20 <= a["high"])
query("de", "constraint", "Schütz für 11 kW Motor, Spule 230 V", "contactor >= 11 kW, coil 230 V AC", lambda c, a: c == "contactors" and a["kw"] >= 11 and a["coil"] == "230AC")
query("en", "constraint", "contactor 24 V DC coil at least 7.5 kW", "contactor >= 7.5 kW, coil 24 V DC", lambda c, a: c == "contactors" and a["kw"] >= 7.5 and a["coil"] == "24DC")
query("it", "constraint", "contattore da 4 kW bobina 24 V CC", "contactor 4 kW, coil 24 V DC", lambda c, a: c == "contactors" and a["kw"] == 4 and a["coil"] == "24DC")
query("de", "constraint", "O-Ring für 180 °C Heißöl 20 x 2,5", "O-ring 20 x 2.5, rated >= 180 °C", lambda c, a: c == "orings" and a["id"] == 20 and a["cs"] == 2.5 and a["t_max"] >= 180)
query("en", "constraint", "O-ring for hot water and steam 30 x 3", "O-ring 30 x 3, EPDM (water and steam)", oring(30, 3, ["EPDM"]))
query("fr", "constraint", "joint torique huile moteur 10 x 2", "O-ring 10 x 2, oil-resistant (NBR or FKM)", oring(10, 2, ["NBR", "FKM"]))
query("it", "constraint", "pressacavo per cavo da 10 mm IP68", "gland range contains 10 mm", lambda c, a: c == "glands" and a["low"] <= 10 <= a["high"])
query("de", "constraint", "Kabelverschraubung für 15 mm Kabel Messing", "gland range contains 15 mm, brass", lambda c, a: c == "glands" and a["low"] <= 15 <= a["high"] and a["material"] == "MS")
query("en", "constraint", "cable gland for 5 mm cable", "gland range contains 5 mm", lambda c, a: c == "glands" and a["low"] <= 5 <= a["high"])
query("fr", "constraint", "contacteur 15 kW bobine 230 V", "contactor 15 kW, coil 230 V AC", lambda c, a: c == "contactors" and a["kw"] == 15 and a["coil"] == "230AC")

# needs described by use, not by product name
query("de", "need", "lebensmittelechtes Fett für Bäckereimaschine", "grease NSF H1", lambda c, a: c == "greases" and a["type"] == "H1")
query("en", "need", "grease for electric motor bearings", "electric motor bearing grease", lambda c, a: c == "greases" and a["type"] == "EM")
query("fr", "need", "graisse haute température 180 °C", "high-temperature grease (to 200 °C)", lambda c, a: c == "greases" and a["type"] == "HT")
query("it", "need", "grasso multiuso cartuccia 400 g", "grease EP2, 400 g", lambda c, a: c == "greases" and a["type"] == "EP2" and a["grams"] == 400)
query("de", "need", "Dichtring für Hydrauliköl 15 x 2,5", "O-ring 15 x 2.5, oil-resistant (NBR or FKM)", oring(15, 2.5, ["NBR", "FKM"]))
query("en", "need", "fitting to join two 8 mm air tubes", "fitting union, 8 mm", lambda c, a: c == "fittings" and a["shape"] == "union" and a["od"] == 8)

# cross-language exact asks, rotated over languages: the same product family asked for in all four
SCREW_ASKS = ["socket head cap screw M{m} x {l} {mat}", "Zylinderschraube Innensechskant M{m} x {l} {mat}",
              "vis tête cylindrique six pans creux M{m} x {l} {mat}", "vite testa cilindrica esagono incassato M{m} x {l} {mat}"]
MAT_WORDS = {"8.8": ["zinc plated", "verzinkt", "zinguée", "zincata"], "A2": ["A2", "A2", "A2", "A2"], "A4": ["A4", "A4", "A4", "A4"]}
for i, (m, length, material) in enumerate([(4, 16, "A2"), (5, 20, "8.8"), (6, 30, "A4"), (8, 25, "A2"), (8, 35, "8.8"), (10, 50, "A4"), (12, 40, "A2"), (6, 40, "8.8")]):
    lang_index = i % 4
    query(LANGS[lang_index], "cross", SCREW_ASKS[lang_index].format(m=m, l=length, mat=MAT_WORDS[material][lang_index]),
          f"screw M{m} x {length}, {material}", screw(m, length, [material]))
ORING_ASKS = ["O-ring {i} x {s} {mat}", "O-Ring {i} x {s} {mat}", "joint torique {i} x {s} {mat}", "anello OR {i} x {s} {mat}"]
for i, (inner, section, material) in enumerate([(8, 2, "FKM"), (12, 2, "EPDM"), (25, 3, "NBR"), (40, 3.5, "FKM"), (50, 3.5, "EPDM"), (5, 1.5, "NBR")]):
    lang_index = (i + 1) % 4
    lang = LANGS[lang_index]
    query(lang, "cross", ORING_ASKS[lang_index].format(i=num(inner, lang), s=num(section, lang), mat=material),
          f"O-ring {inner} x {section}, {material}", oring(inner, section, [material]))
BEARING_ASKS = ["deep groove ball bearing {d} x {D}", "Rillenkugellager {d} x {D}", "roulement à billes {d} x {D}", "cuscinetto a sfere {d} x {D}"]
for i, designation in enumerate(["6001", "6202", "6206", "6303"]):
    d, outer, _ = BEARINGS[designation]
    lang_index = (i + 2) % 4
    query(LANGS[lang_index], "cross", BEARING_ASKS[lang_index].format(d=d, D=outer), f"bearing {d} x {outer}, any seal", bearing(d=d, outer=outer))

# --- outputs ---------------------------------------------------------------------------------------------------
codes = [p["code"] for p in products]
assert len(codes) == len(set(codes)), "duplicate product codes"
for q in queries:
    assert q["expected"], f"{q['id']} '{q['query']}' matches no product: {q['predicate']}"


def quoted(text):
    assert '"' not in text, text  # ';' is fine inside quotes
    return f'"{text}"'


with open(os.path.join(ROOT, "data", "parts.json"), "w", encoding="utf-8") as f:
    json.dump(products, f, ensure_ascii=False, indent=1)

catalog_version = f"{CATALOG}:Staged"
lines = [
    "# Generated by data/generate.py from data/parts.json; do not edit by hand.",
    f"# B2B parts test catalog: {len(products)} products in {len(CATEGORIES) - 1} categories, EN/DE/FR/IT. Invented brands,",
    "# real standard designations and dimension tables. Test data by Dawid Michałowicz, Apache-2.0.",
    "INSERT_UPDATE Language; isocode[unique = true]; active",
    *[f"; {lang} ; true" for lang in LANGS],
    "INSERT_UPDATE Catalog; id[unique = true]",
    f"; {CATALOG}",
    "INSERT_UPDATE CatalogVersion; catalog(id)[unique = true]; version[unique = true]",
    f"; {CATALOG} ; Staged",
    "",
    "INSERT_UPDATE Category; code[unique = true]; catalogVersion(catalog(id), version)[unique = true]; "
    + "; ".join(f"name[lang = {lang}]" for lang in LANGS) + "; supercategories(code, catalogVersion(catalog(id), version))",
]
for code, names in CATEGORIES.items():
    parent = "" if code == "parts" else f"parts:{catalog_version}"
    lines.append(f"; {code} ; {catalog_version} ; " + " ; ".join(quoted(n) for n in names) + f" ; {parent}")
lines += ["",
          "INSERT_UPDATE Product; code[unique = true]; catalogVersion(catalog(id), version)[unique = true]; "
          + "; ".join(f"name[lang = {lang}]" for lang in LANGS) + "; "
          + "; ".join(f"description[lang = {lang}]" for lang in LANGS)
          + "; supercategories(code, catalogVersion(catalog(id), version)); manufacturerName"]
for p in products:
    lines.append(f"; {p['code']} ; {catalog_version} ; " + " ; ".join(quoted(p["name"][lang]) for lang in LANGS) + " ; "
                 + " ; ".join(quoted(p["description"][lang]) for lang in LANGS) + f" ; {p['category']}:{catalog_version} ; {p['brand']}")
with open(os.path.join(ROOT, "data", "parts-catalog.impex"), "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")

with open(os.path.join(ROOT, "eval", "queries.tsv"), "w", encoding="utf-8") as f:
    f.write("id\tlang\tkind\tquery\texpected\tpredicate\n")
    for q in queries:
        f.write("\t".join(q[k] for k in ("id", "lang", "kind", "query", "expected", "predicate")) + "\n")

by_kind = {}
for q in queries:
    by_kind[q["kind"]] = by_kind.get(q["kind"], 0) + 1
print(f"{len(products)} products, {len(queries)} queries {by_kind}; expected set sizes "
      f"{min(len(q['expected'].split('|')) for q in queries)}-{max(len(q['expected'].split('|')) for q in queries)}")
