export type LatLng = { lat: number; lng: number }

export type MalaysiaState = {
  id: string
  name: string
  lat: number
  lng: number
  cities: string[]
}

const CITY_COORDS: Record<string, LatLng> = {
  kangar: { lat: 6.4414, lng: 100.1986 },
  arau: { lat: 6.4297, lng: 100.2698 },
  'kuala perlis': { lat: 6.4001, lng: 100.1288 },
  'alor setar': { lat: 6.1248, lng: 100.3678 },
  'sungai petani': { lat: 5.647, lng: 100.4877 },
  kulim: { lat: 5.3649, lng: 100.5618 },
  langkawi: { lat: 6.35, lng: 99.8 },
  'george town': { lat: 5.4141, lng: 100.3288 },
  georgetown: { lat: 5.4141, lng: 100.3288 },
  butterworth: { lat: 5.3991, lng: 100.3638 },
  'bukit mertajam': { lat: 5.363, lng: 100.4667 },
  'bayan lepas': { lat: 5.2945, lng: 100.2593 },
  'kota bharu': { lat: 6.1254, lng: 102.2381 },
  'pasir mas': { lat: 6.0493, lng: 102.1399 },
  'tanah merah': { lat: 5.8089, lng: 102.1471 },
  'kuala terengganu': { lat: 5.3296, lng: 103.137 },
  dungun: { lat: 4.769, lng: 103.416 },
  kemaman: { lat: 4.233, lng: 103.422 },
  ipoh: { lat: 4.5975, lng: 101.0901 },
  taiping: { lat: 4.85, lng: 100.7333 },
  'teluk intan': { lat: 4.0224, lng: 101.0206 },
  sitiawan: { lat: 4.2167, lng: 100.7 },
  kuantan: { lat: 3.8077, lng: 103.326 },
  temerloh: { lat: 3.45, lng: 102.4167 },
  bentong: { lat: 3.5222, lng: 101.9089 },
  'cameron highlands': { lat: 4.4721, lng: 101.3802 },
  'shah alam': { lat: 3.0733, lng: 101.5185 },
  'petaling jaya': { lat: 3.1073, lng: 101.6067 },
  'subang jaya': { lat: 3.0438, lng: 101.5806 },
  klang: { lat: 3.0449, lng: 101.4456 },
  ampang: { lat: 3.1488, lng: 101.763 },
  cheras: { lat: 3.1069, lng: 101.7259 },
  'kuala lumpur': { lat: 3.139, lng: 101.6869 },
  kepong: { lat: 3.214, lng: 101.635 },
  bangsar: { lat: 3.1301, lng: 101.6701 },
  putrajaya: { lat: 2.9264, lng: 101.6964 },
  cyberjaya: { lat: 2.9225, lng: 101.655 },
  seremban: { lat: 2.7259, lng: 101.9378 },
  'port dickson': { lat: 2.5226, lng: 101.795 },
  nilai: { lat: 2.8134, lng: 101.796 },
  melaka: { lat: 2.1896, lng: 102.2501 },
  malacca: { lat: 2.1896, lng: 102.2501 },
  'alor gajah': { lat: 2.3804, lng: 102.2089 },
  jasin: { lat: 2.309, lng: 102.429 },
  'johor bahru': { lat: 1.4927, lng: 103.7414 },
  'batu pahat': { lat: 1.8548, lng: 102.9325 },
  muar: { lat: 2.0442, lng: 102.5689 },
  kluang: { lat: 2.0301, lng: 103.321 },
  'pasir gudang': { lat: 1.4706, lng: 103.901 },
  kuching: { lat: 1.5533, lng: 110.3592 },
  miri: { lat: 4.3995, lng: 113.9914 },
  sibu: { lat: 2.2873, lng: 111.8305 },
  bintulu: { lat: 3.1713, lng: 113.0416 },
  'kota kinabalu': { lat: 5.9804, lng: 116.0735 },
  sandakan: { lat: 5.8394, lng: 118.1171 },
  tawau: { lat: 4.2448, lng: 117.8912 },
  'lahad datu': { lat: 5.0267, lng: 118.327 },
  labuan: { lat: 5.2831, lng: 115.2308 },
}

export const MALAYSIA_STATES: MalaysiaState[] = [
  {
    id: 'perlis',
    name: 'Perlis',
    lat: 6.4449,
    lng: 100.205,
    cities: ['Kangar', 'Arau', 'Kuala Perlis'],
  },
  {
    id: 'kedah',
    name: 'Kedah',
    lat: 6.1184,
    lng: 100.3685,
    cities: ['Alor Setar', 'Sungai Petani', 'Kulim', 'Langkawi'],
  },
  {
    id: 'penang',
    name: 'Pulau Pinang',
    lat: 5.4141,
    lng: 100.3288,
    cities: ['George Town', 'Butterworth', 'Bukit Mertajam', 'Bayan Lepas'],
  },
  {
    id: 'kelantan',
    name: 'Kelantan',
    lat: 6.1254,
    lng: 102.2381,
    cities: ['Kota Bharu', 'Pasir Mas', 'Tanah Merah'],
  },
  {
    id: 'terengganu',
    name: 'Terengganu',
    lat: 5.3117,
    lng: 103.1324,
    cities: ['Kuala Terengganu', 'Dungun', 'Kemaman'],
  },
  {
    id: 'perak',
    name: 'Perak',
    lat: 4.5975,
    lng: 101.0901,
    cities: ['Ipoh', 'Taiping', 'Teluk Intan', 'Sitiawan'],
  },
  {
    id: 'pahang',
    name: 'Pahang',
    lat: 3.8126,
    lng: 103.3256,
    cities: ['Kuantan', 'Temerloh', 'Bentong', 'Cameron Highlands'],
  },
  {
    id: 'selangor',
    name: 'Selangor',
    lat: 3.0738,
    lng: 101.5183,
    cities: ['Shah Alam', 'Petaling Jaya', 'Subang Jaya', 'Klang', 'Ampang', 'Cheras'],
  },
  {
    id: 'kuala-lumpur',
    name: 'Wilayah Persekutuan Kuala Lumpur',
    lat: 3.139,
    lng: 101.6869,
    cities: ['Kuala Lumpur', 'Cheras', 'Kepong', 'Bangsar'],
  },
  {
    id: 'putrajaya',
    name: 'Wilayah Persekutuan Putrajaya',
    lat: 2.9264,
    lng: 101.6964,
    cities: ['Putrajaya', 'Cyberjaya'],
  },
  {
    id: 'negeri-sembilan',
    name: 'Negeri Sembilan',
    lat: 2.7258,
    lng: 101.9424,
    cities: ['Seremban', 'Port Dickson', 'Nilai'],
  },
  {
    id: 'melaka',
    name: 'Melaka',
    lat: 2.1896,
    lng: 102.2501,
    cities: ['Melaka', 'Alor Gajah', 'Jasin'],
  },
  {
    id: 'johor',
    name: 'Johor',
    lat: 1.4927,
    lng: 103.7414,
    cities: ['Johor Bahru', 'Batu Pahat', 'Muar', 'Kluang', 'Pasir Gudang'],
  },
  {
    id: 'sarawak',
    name: 'Sarawak',
    lat: 1.5533,
    lng: 110.3592,
    cities: ['Kuching', 'Miri', 'Sibu', 'Bintulu'],
  },
  {
    id: 'sabah',
    name: 'Sabah',
    lat: 5.9804,
    lng: 116.0735,
    cities: ['Kota Kinabalu', 'Sandakan', 'Tawau', 'Lahad Datu'],
  },
  {
    id: 'labuan',
    name: 'Wilayah Persekutuan Labuan',
    lat: 5.2831,
    lng: 115.2308,
    cities: ['Labuan'],
  },
]

/** Default view covering Peninsular + East Malaysia */
export const MALAYSIA_BOUNDS: [[number, number], [number, number]] = [
  [0.8, 99.5],
  [7.5, 119.5],
]

function normalize(value: string) {
  return value.trim().toLowerCase().replace(/\s+/g, ' ')
}

export function findMalaysiaState(province?: string | null) {
  if (!province?.trim()) return null
  const key = normalize(province)
  return (
    MALAYSIA_STATES.find(
      (state) =>
        normalize(state.name) === key ||
        normalize(state.name).includes(key) ||
        key.includes(normalize(state.id.replaceAll('-', ' '))) ||
        (key.includes('kuala lumpur') && state.id === 'kuala-lumpur') ||
        (key.includes('penang') && state.id === 'penang') ||
        (key.includes('pulau pinang') && state.id === 'penang') ||
        (key.includes('putrajaya') && state.id === 'putrajaya') ||
        (key.includes('labuan') && state.id === 'labuan'),
    ) ?? null
  )
}

export function citiesForState(province: string) {
  return findMalaysiaState(province)?.cities ?? []
}

export function latLngForSite(site: {
  id: string
  province?: string | null
  city?: string | null
}): { lat: number; lng: number; state: MalaysiaState | null; known: boolean } {
  const cityKey = site.city ? normalize(site.city) : ''
  const city = cityKey ? CITY_COORDS[cityKey] : null
  if (city) {
    return {
      lat: city.lat,
      lng: city.lng,
      state: findMalaysiaState(site.province),
      known: true,
    }
  }

  const state = findMalaysiaState(site.province)
  if (!state) {
    return { lat: 4.2105, lng: 101.9758, state: null, known: false }
  }

  // Slight offset so multiple units in the same state don't stack perfectly
  let hash = 0
  for (let i = 0; i < site.id.length; i += 1) hash = (hash * 31 + site.id.charCodeAt(i)) >>> 0
  const olat = ((hash % 100) / 100 - 0.5) * 0.12
  const olng = ((((hash / 100) % 100) / 100) - 0.5) * 0.12

  return {
    lat: state.lat + olat,
    lng: state.lng + olng,
    state,
    known: true,
  }
}
