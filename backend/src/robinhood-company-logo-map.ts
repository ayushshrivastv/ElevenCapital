// Canonical company-image fallbacks reviewed against the public issuer feed.
// Source identity, image hashes, and rejected responses: ROBINHOOD_COMPANY_LOGOS.json.
interface ReviewedCompanyLogo { symbol: string; isin: string; contract: string; issuerLogoUrl: string; companyLogoUrl: string }
export const reviewedRobinhoodCompanyLogos: Readonly<Record<string, ReviewedCompanyLogo>> = {
  "0x00000000000000000000000000000000022015c295294037bfe416d3e45327b9": {
    "symbol": "CRM",
    "isin": "US79466L3024",
    "contract": "0xd95b44124e475743a7589e68f3d74008a5536d44",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xd95b44124e475743a7589e68f3d74008a5536d44.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/CRM.png"
  },
  "0x0000000000000000000000000000000002c4d1ce31ec4310b2c507c921a52b70": {
    "symbol": "P",
    "isin": "US74624M1027",
    "contract": "0x1cdad396db64bda184d5182a97dd9b3c62100b7d",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x1cdad396db64bda184d5182a97dd9b3c62100b7d.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/P.png"
  },
  "0x0000000000000000000000000000000003bd94e037654c6a9828af2137edd3c3": {
    "symbol": "CIEN",
    "isin": "US1717793095",
    "contract": "0x44f6d488021f8233b9416294d1fe9b1fee28382d",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x44f6d488021f8233b9416294d1fe9b1fee28382d.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/CIEN.png"
  },
  "0x000000000000000000000000000000000467fac1d4da4c0e9ae874314fc8d784": {
    "symbol": "ON",
    "isin": "US6821891057",
    "contract": "0xbbd09f72b025360fee5c928053dca6248d35be54",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xbbd09f72b025360fee5c928053dca6248d35be54.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/ON.png"
  },
  "0x0000000000000000000000000000000004a960b45163413698f0f69b5454785d": {
    "symbol": "APLD",
    "isin": "US0381692070",
    "contract": "0xb8dbf92f9741c9ac1c32115e78581f23509916fd",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xb8dbf92f9741c9ac1c32115e78581f23509916fd.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/APLD.png"
  },
  "0x000000000000000000000000000000000747a25837b6475c8da1a66b7d036988": {
    "symbol": "AEHR",
    "isin": "US00760J1088",
    "contract": "0x5f604fba1162193a4388a5dfa56f556f3e133cc2",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x5f604fba1162193a4388a5dfa56f556f3e133cc2.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/AEHR.png"
  },
  "0x0000000000000000000000000000000007a456595d454eafba36fa1fb508973e": {
    "symbol": "TEAM",
    "isin": "US0494681010",
    "contract": "0x5b97476b922f3305131b8f0b9d333172e87f4aae",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x5b97476b922f3305131b8f0b9d333172e87f4aae.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/TEAM.png"
  },
  "0x000000000000000000000000000000000a924e0d878747e586c22887bc2d82b1": {
    "symbol": "QBTS",
    "isin": "US26740W1099",
    "contract": "0xc583c60aef9dc401da72cec1b404743a93cea1cc",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xc583c60aef9dc401da72cec1b404743a93cea1cc.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/QBTS.png"
  },
  "0x000000000000000000000000000000000ca816dd2ffb4c279c52c5fa87bd431e": {
    "symbol": "FIX",
    "isin": "US1999081045",
    "contract": "0x93dbb1d2dc5d63f4abacff30485273f538df68ac",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x93dbb1d2dc5d63f4abacff30485273f538df68ac.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/FIX.png"
  },
  "0x000000000000000000000000000000000cd0e8a5e7c341e09f4532de2dc256b2": {
    "symbol": "MTSI",
    "isin": "US55405Y1001",
    "contract": "0xc93f4d80e268ab922e871bd169156c3cc41894e6",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xc93f4d80e268ab922e871bd169156c3cc41894e6.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/MTSI.png"
  },
  "0x000000000000000000000000000000000cfe41021541408ab92f19e6af2fa729": {
    "symbol": "SMCI",
    "isin": "US86800U3023",
    "contract": "0xc01aa1fecec0605b13bc84874ff7256c0f5f562a",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xc01aa1fecec0605b13bc84874ff7256c0f5f562a.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/SMCI.png"
  },
  "0x0000000000000000000000000000000014eaef73a2c44d62b7952cfd0a44ff51": {
    "symbol": "DELL",
    "isin": "US24703L2025",
    "contract": "0x941ae714ec6d8130c7b75d67160ca08f1e7d11dd",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x941ae714ec6d8130c7b75d67160ca08f1e7d11dd.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/DELL.png"
  },
  "0x0000000000000000000000000000000014f96c01ad254647b2617a5686d6053a": {
    "symbol": "MXL",
    "isin": "US57776J1007",
    "contract": "0x48961813349333209994750ffa89b3c5c22ec969",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x48961813349333209994750ffa89b3c5c22ec969.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/MXL.png"
  },
  "0x00000000000000000000000000000000159fb9b112cb4ac8acec13e01d48e763": {
    "symbol": "RUN",
    "isin": "US86771W1053",
    "contract": "0x756bc80af765c82da966a788858d65adf14f3793",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x756bc80af765c82da966a788858d65adf14f3793.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/RUN.png"
  },
  "0x000000000000000000000000000000001805c8a4eb1a4b3fa49e2698c0da6baa": {
    "symbol": "WDAY",
    "isin": "US98138H1014",
    "contract": "0x82da4646242e1d962e96e932269dc644c94a9caa",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x82da4646242e1d962e96e932269dc644c94a9caa.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/WDAY.png"
  },
  "0x000000000000000000000000000000001aa9c9cc0bf34c5e95cfe7168463d310": {
    "symbol": "SPCX",
    "isin": "US84615Q1031",
    "contract": "0x4a0e65a3eccec6dbe60ae065f2e7bb85fae35eea",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x4a0e65a3eccec6dbe60ae065f2e7bb85fae35eea.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/SPCX.png"
  },
  "0x000000000000000000000000000000001acf6eeea06d40c5b323c903f77bd6d1": {
    "symbol": "GLXY",
    "isin": "US36317J2096",
    "contract": "0x2d427692e928fa156ec22acfabafa0447c5805b7",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x2d427692e928fa156ec22acfabafa0447c5805b7.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/GLXY.png"
  },
  "0x000000000000000000000000000000001adecf2c6a3749f9873b8926b5977c0a": {
    "symbol": "CRWD",
    "isin": "US22788C1053",
    "contract": "0xea72ecca2d0f6bfa1394dbbcff85b52cd4233931",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xea72ecca2d0f6bfa1394dbbcff85b52cd4233931.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/CRWD.png"
  },
  "0x000000000000000000000000000000001bbb45628de84cb2917abd26680c7ab9": {
    "symbol": "AVGO",
    "isin": "US11135F1012",
    "contract": "0x156e175dd063a8ce274c50654ef40e0032b3fbcf",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x156e175dd063a8ce274c50654ef40e0032b3fbcf.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/AVGO.png"
  },
  "0x000000000000000000000000000000001c6f27a62789417d8ed359ed3c2d3da1": {
    "symbol": "SPY",
    "isin": "US78462F1030",
    "contract": "0x117cc2133c37b721f49de2a7a74833232b3b4c0c",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x117cc2133c37b721f49de2a7a74833232b3b4c0c.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/SPY.png"
  },
  "0x000000000000000000000000000000001cf204e4aa464340bf2e68461ff6d8d3": {
    "symbol": "CCL",
    "isin": "BMG2004J1036",
    "contract": "0x9651342cea770ae9a2969ba2a52611523146aef9",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x9651342cea770ae9a2969ba2a52611523146aef9.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/CCL.png"
  },
  "0x000000000000000000000000000000002087239447ce4fcb8c6194c8941d287c": {
    "symbol": "SNOW",
    "isin": "US8334451098",
    "contract": "0xba0cab75495255d0cb58e22b648bfed4ecd1f47e",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xba0cab75495255d0cb58e22b648bfed4ecd1f47e.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/SNOW.png"
  },
  "0x0000000000000000000000000000000021d840250ab344d198503610448348fe": {
    "symbol": "FICO",
    "isin": "US3032501047",
    "contract": "0xa48f22a46c0f1c46ca7d111cb6c137c271987180",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xa48f22a46c0f1c46ca7d111cb6c137c271987180.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/FICO.png"
  },
  "0x0000000000000000000000000000000022d137a64ac743aa991898cc4c1d7af4": {
    "symbol": "PR",
    "isin": "US71424F1057",
    "contract": "0x4189f0c66ebbb0bfef1c31f763131361ef32f77c",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x4189f0c66ebbb0bfef1c31f763131361ef32f77c.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/PR.png"
  },
  "0x000000000000000000000000000000002470b933c52d47ccad017ed9ee80c9ed": {
    "symbol": "QQQ",
    "isin": "US46090E1038",
    "contract": "0xd5f3879160bc7c32ebb4dc785f8a4f505888de68",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xd5f3879160bc7c32ebb4dc785f8a4f505888de68.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/QQQ.png"
  },
  "0x0000000000000000000000000000000025191a136aab4e5fa41b67ec3218a093": {
    "symbol": "WYFI",
    "isin": "KYG961151035",
    "contract": "0x9e7abd3c9139d14e4c86dce0e455aab7a0c2fb3e",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x9e7abd3c9139d14e4c86dce0e455aab7a0c2fb3e.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/WYFI.png"
  },
  "0x000000000000000000000000000000002850ddf4a89a4195905c49a4ef7af8a5": {
    "symbol": "NNE",
    "isin": "US63010H1086",
    "contract": "0xbef75684c43c4ea7bd18dd532a2244674ee8b926",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xbef75684c43c4ea7bd18dd532a2244674ee8b926.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/NNE.png"
  },
  "0x00000000000000000000000000000000289ac6c307ef486d8201efa27fa14d63": {
    "symbol": "GE",
    "isin": "US3696043013",
    "contract": "0x63b814ddbd6bf339f25fed8c36158a008d5b373e",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x63b814ddbd6bf339f25fed8c36158a008d5b373e.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/GE.png"
  },
  "0x000000000000000000000000000000002993bb34578a4b92b8addda0fc4d697b": {
    "symbol": "SNAP",
    "isin": "US83304A1060",
    "contract": "0xf6589f11bc40b669e584073f428b05562f568733",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xf6589f11bc40b669e584073f428b05562f568733.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/SNAP.png"
  },
  "0x000000000000000000000000000000002b98fc3881db4479b3827d5ca058d31a": {
    "symbol": "SKHY",
    "isin": "US78392B2060",
    "contract": "0x84cab63bc87912e71ad199ff14a0ba45de68fef8",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x84cab63bc87912e71ad199ff14a0ba45de68fef8.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/SKHY.png"
  },
  "0x000000000000000000000000000000002e030d9bc86c45e2b9e02418dd312548": {
    "symbol": "CBRS",
    "isin": "US15675D1037",
    "contract": "0x5c90450bbb4273d7b2f17cf6917aeb237a569679",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x5c90450bbb4273d7b2f17cf6917aeb237a569679.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/CBRS.png"
  },
  "0x000000000000000000000000000000002e3b98468b2d49bea9b69a2b92228fa6": {
    "symbol": "FUTU",
    "isin": "US36118L1061",
    "contract": "0xeb30663bdff0622ef4e4e5cbb4e975f19f33f51d",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xeb30663bdff0622ef4e4e5cbb4e975f19f33f51d.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/FUTU.png"
  },
  "0x000000000000000000000000000000002e890a0884474abd86ba66b19722e827": {
    "symbol": "TSM",
    "isin": "US8740391003",
    "contract": "0x58ffe4a942d3885baa22d7520691f611ef09e7aa",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x58ffe4a942d3885baa22d7520691f611ef09e7aa.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/TSM.png"
  },
  "0x000000000000000000000000000000002ea76ec420fa43ab81d6734184f7058a": {
    "symbol": "NU",
    "isin": "KYG6683N1034",
    "contract": "0x408c14038a04f7bd235329e26d2bf569ee20e250",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x408c14038a04f7bd235329e26d2bf569ee20e250.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/NU.png"
  },
  "0x000000000000000000000000000000002eb75a2c20d7423881b4f812c27d0abe": {
    "symbol": "INTC",
    "isin": "US4581401001",
    "contract": "0xc72b96e0e48ecd4dc75e1e45396e26300bc39681",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xc72b96e0e48ecd4dc75e1e45396e26300bc39681.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/INTC.png"
  },
  "0x00000000000000000000000000000000307bb0113ca54f93adf80f4ff2bf681a": {
    "symbol": "MSFT",
    "isin": "US5949181045",
    "contract": "0xe93237c50d904957cf27e7b1133b510c669c2e74",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xe93237c50d904957cf27e7b1133b510c669c2e74.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/MSFT.png"
  },
  "0x00000000000000000000000000000000311078f293cf441789d7bc7eb9480228": {
    "symbol": "RCAT",
    "isin": "US75644T1007",
    "contract": "0xfde6b5d9bb419b10c23268c74e369abff39c0460",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xfde6b5d9bb419b10c23268c74e369abff39c0460.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/RCAT.png"
  },
  "0x0000000000000000000000000000000031a6c09d095b4d49b4155de0040da68f": {
    "symbol": "RGTI",
    "isin": "US76655K1034",
    "contract": "0x284358abc07f9359f19f4b5b4ac91901be2597ba",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x284358abc07f9359f19f4b5b4ac91901be2597ba.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/RGTI.png"
  },
  "0x0000000000000000000000000000000032d0504fcc1a48ff8ac9b29185578559": {
    "symbol": "TTWO",
    "isin": "US8740541094",
    "contract": "0x5e81213613b6b86eab4c6c50d718d34359459786",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x5e81213613b6b86eab4c6c50d718d34359459786.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/TTWO.png"
  },
  "0x00000000000000000000000000000000343e7beca03644bcba2e50b8236784f5": {
    "symbol": "META",
    "isin": "US30303M1027",
    "contract": "0xc0d6457c16cc70d6790dd43521c899c87ce02f35",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xc0d6457c16cc70d6790dd43521c899c87ce02f35.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/META.png"
  },
  "0x0000000000000000000000000000000034a1e548dfdc4b7cbf162648f20ccffc": {
    "symbol": "VICR",
    "isin": "US9258151029",
    "contract": "0x6006ed4b2f94110851ff7509d97d034f0eed9226",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x6006ed4b2f94110851ff7509d97d034f0eed9226.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/VICR.png"
  },
  "0x000000000000000000000000000000003bc66ed8f2314091a365604b4c01aef5": {
    "symbol": "RDDT",
    "isin": "US75734B1008",
    "contract": "0x05b37fb53a299a1b874a619e1c4c404d52c36f4c",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x05b37fb53a299a1b874a619e1c4c404d52c36f4c.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/RDDT.png"
  },
  "0x0000000000000000000000000000000040b2153b3d3d4715b8d22a15ba1acff2": {
    "symbol": "GLW",
    "isin": "US2193501051",
    "contract": "0x7c04e6a3368f2a1de3874f0e80d2e0a1a9915da6",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x7c04e6a3368f2a1de3874f0e80d2e0a1a9915da6.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/GLW.png"
  },
  "0x00000000000000000000000000000000413cbb2a5e924576a297ccb67c272298": {
    "symbol": "UPS",
    "isin": "US9113121068",
    "contract": "0xf23250dac154d05bb671cb0d0ebef3c635c79ce2",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xf23250dac154d05bb671cb0d0ebef3c635c79ce2.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/UPS.png"
  },
  "0x00000000000000000000000000000000423f62d0b1e146a3b90e9b1ffcbf6e16": {
    "symbol": "PANW",
    "isin": "US6974351057",
    "contract": "0xb039597ed45cba7b6e2fb9e8be51802969cee5be",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xb039597ed45cba7b6e2fb9e8be51802969cee5be.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/PANW.png"
  },
  "0x00000000000000000000000000000000441407e9a0ab483bb0d6191d23bcb654": {
    "symbol": "LHX",
    "isin": "US5024311095",
    "contract": "0x48d60243c66437c6ac3c2495be94747aed5dfe25",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x48d60243c66437c6ac3c2495be94747aed5dfe25.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/LHX.png"
  },
  "0x00000000000000000000000000000000450b2a4327ce4062b527224efc49589a": {
    "symbol": "FLY",
    "isin": "US31816X1063",
    "contract": "0x03bc731ffb162cdd7b98d3c6542bfc291126075d",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x03bc731ffb162cdd7b98d3c6542bfc291126075d.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/FLY.png"
  },
  "0x0000000000000000000000000000000046e46cc63fe842ebbdfe6187bda403d6": {
    "symbol": "SNDK",
    "isin": "US80004C2008",
    "contract": "0xb90a19ff0af67f7779aff50a882a9cff42446400",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xb90a19ff0af67f7779aff50a882a9cff42446400.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/SNDK.png"
  },
  "0x0000000000000000000000000000000047129a8e0f8f4091841e4004bfa08013": {
    "symbol": "BA",
    "isin": "US0970231058",
    "contract": "0x4d21483a44bf67a86b77e3da301411880797d452",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x4d21483a44bf67a86b77e3da301411880797d452.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/BA.png"
  },
  "0x00000000000000000000000000000000482927a418d1476c8ee89e119bf61fc7": {
    "symbol": "INTU",
    "isin": "US4612021034",
    "contract": "0x56d23bee5f41a7120170b0c603dae30128e460e9",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x56d23bee5f41a7120170b0c603dae30128e460e9.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/INTU.png"
  },
  "0x000000000000000000000000000000004ed544ce43734899971a27f98af8972e": {
    "symbol": "ELF",
    "isin": "US26856L1035",
    "contract": "0x39ec44bee4f6a116c6f9b8de566848a985c53c60",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x39ec44bee4f6a116c6f9b8de566848a985c53c60.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/ELF.png"
  },
  "0x000000000000000000000000000000004f508d5e2a7042299694d85c2f362ad2": {
    "symbol": "AMZN",
    "isin": "US0231351067",
    "contract": "0x12f190a9f9d7d37a250758b26824b97ce941bf54",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x12f190a9f9d7d37a250758b26824b97ce941bf54.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/AMZN.png"
  },
  "0x00000000000000000000000000000000500f14b2a92f44d6998e0e2b9cc9387e": {
    "symbol": "NFLX",
    "isin": "US64110L1061",
    "contract": "0xe0444ef8bf4ed74f74fd73686e2ddf4c1c5591e8",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xe0444ef8bf4ed74f74fd73686e2ddf4c1c5591e8.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/NFLX.png"
  },
  "0x000000000000000000000000000000005047f8f8a1a34a9ebee52a4155570807": {
    "symbol": "CRCL",
    "isin": "US1725731079",
    "contract": "0xdf0992e440dd0be65bd8439b609d6d4366bf1cb5",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xdf0992e440dd0be65bd8439b609d6d4366bf1cb5.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/CRCL.png"
  },
  "0x0000000000000000000000000000000051d8408eb0484cb6bd003337551fddd5": {
    "symbol": "USAR",
    "isin": "US91733P1075",
    "contract": "0xd917b029c761d264c6a312bbbcda868658ef86a6",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xd917b029c761d264c6a312bbbcda868658ef86a6.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/USAR.png"
  },
  "0x0000000000000000000000000000000051fd963e99794a83a5e429d05ddc0347": {
    "symbol": "VST",
    "isin": "US92840M1027",
    "contract": "0x561e2a49212b7ccf47f2744ccb83e200722fadbc",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x561e2a49212b7ccf47f2744ccb83e200722fadbc.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/VST.png"
  },
  "0x0000000000000000000000000000000052b1bd2848fd4837baefc662660c6b28": {
    "symbol": "CSCO",
    "isin": "US17275R1023",
    "contract": "0xf543967eebb6f1917992ef0e68de63ab07a5a0da",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xf543967eebb6f1917992ef0e68de63ab07a5a0da.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/CSCO.png"
  },
  "0x0000000000000000000000000000000053906f8ddb574007aea297c4467f567b": {
    "symbol": "FISV",
    "isin": "US3377381088",
    "contract": "0x9ece29a4a2397c0a35fb5fa8ee2b9509130a98cc",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x9ece29a4a2397c0a35fb5fa8ee2b9509130a98cc.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/FISV.png"
  },
  "0x0000000000000000000000000000000053b69e2076884cc9ae2ada9bc7095df3": {
    "symbol": "GOOGL",
    "isin": "US02079K3059",
    "contract": "0x2e0847e8910a9732eb3fb1bb4b70a580adad4fe3",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x2e0847e8910a9732eb3fb1bb4b70a580adad4fe3.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/GOOGL.png"
  },
  "0x00000000000000000000000000000000551774850f194601b6cdefc8c802696d": {
    "symbol": "BB",
    "isin": "CA09228F1036",
    "contract": "0x48e39e56acdba37b09020c0b734a613c9a2f100a",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x48e39e56acdba37b09020c0b734a613c9a2f100a.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/BB.png"
  },
  "0x00000000000000000000000000000000551774b7a07e4f24a8901f266c16a86d": {
    "symbol": "CVNA",
    "isin": "US1468691027",
    "contract": "0xa4f319104089fe321dc8093c6e707d4fe190a988",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xa4f319104089fe321dc8093c6e707d4fe190a988.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/CVNA.png"
  },
  "0x000000000000000000000000000000005756ed74636b4fb7a1cb3bbdbd18eb64": {
    "symbol": "AXTI",
    "isin": "US00246W1036",
    "contract": "0x141eea040c2250eec0314e336975e81f85f6585e",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x141eea040c2250eec0314e336975e81f85f6585e.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/AXTI.png"
  },
  "0x0000000000000000000000000000000059499b681f6d4d558c653682dd74910e": {
    "symbol": "MOD",
    "isin": "US6078281002",
    "contract": "0xc6cbad1016b38b797610c25e1dc7d95988b1f362",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xc6cbad1016b38b797610c25e1dc7d95988b1f362.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/MOD.png"
  },
  "0x000000000000000000000000000000005b0a57398f2f4322b011fb98da1f785f": {
    "symbol": "LULU",
    "isin": "US5500211090",
    "contract": "0x4e62068525ab11fe768e29dfd00ef909b9803016",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x4e62068525ab11fe768e29dfd00ef909b9803016.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/LULU.png"
  },
  "0x000000000000000000000000000000005cca09f06be14aea89a8ea6265026644": {
    "symbol": "LMT",
    "isin": "US5398301094",
    "contract": "0x329fcaceb9ad6f9580dd5f643fed0646900d043c",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x329fcaceb9ad6f9580dd5f643fed0646900d043c.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/LMT.png"
  },
  "0x000000000000000000000000000000005d6501818f804e87ab5b352105bbad0a": {
    "symbol": "INFQ",
    "isin": "US45676K1034",
    "contract": "0xb853bc83a753342a4f8320ea680b4b1e84118d21",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xb853bc83a753342a4f8320ea680b4b1e84118d21.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/INFQ.png"
  },
  "0x0000000000000000000000000000000060d13e3566ea42a0a83a3224fd446109": {
    "symbol": "OUST",
    "isin": "US68989M2026",
    "contract": "0x40e7a279850e443f582059ae5dc1c3b6563e6395",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x40e7a279850e443f582059ae5dc1c3b6563e6395.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/OUST.png"
  },
  "0x00000000000000000000000000000000612c7fa7477f4f21b3a425f180cffd86": {
    "symbol": "IREN",
    "isin": "AU0000185993",
    "contract": "0xf0ab0c93be6f41369d302e55db1a96b3c430212d",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xf0ab0c93be6f41369d302e55db1a96b3c430212d.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/IREN.png"
  },
  "0x00000000000000000000000000000000645f1b5c9e1a4a0e8fa96d05e16df733": {
    "symbol": "MRNA",
    "isin": "US60770K1079",
    "contract": "0x43b07d15ce533bec5476d70c22a78a1b2b662155",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x43b07d15ce533bec5476d70c22a78a1b2b662155.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/MRNA.png"
  },
  "0x0000000000000000000000000000000065968801094d4f8794208f387f3fe9f9": {
    "symbol": "LRCX",
    "isin": "US5128073062",
    "contract": "0x57b0030166db0c31690d1a5aa167e2e26e2c29a4",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x57b0030166db0c31690d1a5aa167e2e26e2c29a4.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/LRCX.png"
  },
  "0x0000000000000000000000000000000068576a5b6aee4a6bbc9f0d91f47e3918": {
    "symbol": "CEG",
    "isin": "US21037T1097",
    "contract": "0xae517a2903e68bd929dfd15be875f8369d53e94a",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xae517a2903e68bd929dfd15be875f8369d53e94a.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/CEG.png"
  },
  "0x0000000000000000000000000000000069bc82da7c264394bdf6b23c8b0cd399": {
    "symbol": "ALAB",
    "isin": "US04626A1034",
    "contract": "0x748c32c3ca24edf31ea597db1f3d330a7a6da3dc",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x748c32c3ca24edf31ea597db1f3d330a7a6da3dc.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/ALAB.png"
  },
  "0x000000000000000000000000000000006ae9a2c6d30a4d379e93169d2441876b": {
    "symbol": "TEM",
    "isin": "US88023B1035",
    "contract": "0xb1cc0ec7db69cf43539119814df40071b9d61793",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xb1cc0ec7db69cf43539119814df40071b9d61793.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/TEM.png"
  },
  "0x000000000000000000000000000000006b598b911bbf43ee8ca6be4dd7267f6d": {
    "symbol": "BULL",
    "isin": "KYG9572D1034",
    "contract": "0xcef9027c7d6985b85f0ba431125073529a947a68",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xcef9027c7d6985b85f0ba431125073529a947a68.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/BULL.png"
  },
  "0x000000000000000000000000000000006d5054df7b5d4f8f8a11cc01084242e7": {
    "symbol": "UNH",
    "isin": "US91324P1021",
    "contract": "0xcf364ea52787e289de6f32077834056e3e70d6a8",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xcf364ea52787e289de6f32077834056e3e70d6a8.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/UNH.png"
  },
  "0x000000000000000000000000000000006e6855f045c84b9cabd44da9108be2f8": {
    "symbol": "CLOV",
    "isin": "US18914F1030",
    "contract": "0x62200915e7deab1ec7f79fb246dadbb80eacddd0",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x62200915e7deab1ec7f79fb246dadbb80eacddd0.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/CLOV.png"
  },
  "0x000000000000000000000000000000006ef9da606f504495ad7de6a7a58b87cd": {
    "symbol": "BABA",
    "isin": "US01609W1027",
    "contract": "0xad25ac6c84d497db898fa1e8387bf6af3532a1c4",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xad25ac6c84d497db898fa1e8387bf6af3532a1c4.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/BABA.png"
  },
  "0x000000000000000000000000000000006f1951b0f7c348a480d9c135679778f7": {
    "symbol": "SIMO",
    "isin": "US82706C1080",
    "contract": "0x77e655e37f4d913fb9540e0d541d824171a60e81",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x77e655e37f4d913fb9540e0d541d824171a60e81.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/SIMO.png"
  },
  "0x000000000000000000000000000000007001c5951f374630b5f3f52fd1ff2022": {
    "symbol": "WDC",
    "isin": "US9581021055",
    "contract": "0xf52597345a8edf418bc4071b4a35112472277d3e",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xf52597345a8edf418bc4071b4a35112472277d3e.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/WDC.png"
  },
  "0x00000000000000000000000000000000730835cf25474184af89c27075a82be1": {
    "symbol": "CLSK",
    "isin": "US18452B2097",
    "contract": "0xcbb95bbf36099d34da091dc6fa6f49efa257cee3",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xcbb95bbf36099d34da091dc6fa6f49efa257cee3.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/CLSK.png"
  },
  "0x000000000000000000000000000000007454a90b4aab491389ce9fa3c485de42": {
    "symbol": "PLTR",
    "isin": "US69608A1088",
    "contract": "0x894e1ec2d74ffe5aef8dc8a9e84686accb964f2a",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x894e1ec2d74ffe5aef8dc8a9e84686accb964f2a.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/PLTR.png"
  },
  "0x000000000000000000000000000000007757415150a94b50a879cdc04dc90664": {
    "symbol": "MU",
    "isin": "US5951121038",
    "contract": "0xff080c8ce2e5feadaca0da81314ae59d232d4afd",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xff080c8ce2e5feadaca0da81314ae59d232d4afd.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/MU.png"
  },
  "0x00000000000000000000000000000000778bc606b4504ca0babd4e171d5262ee": {
    "symbol": "ASTS",
    "isin": "US00217D1000",
    "contract": "0x1af6446f07eb1d97c546afc8c9544cbdf3ad5137",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x1af6446f07eb1d97c546afc8c9544cbdf3ad5137.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/ASTS.png"
  },
  "0x00000000000000000000000000000000794a29855a8f4a9998510dfa2604da94": {
    "symbol": "POWL",
    "isin": "US7391281067",
    "contract": "0x237c16d66590f67b886d978acd362eaead8b18c7",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x237c16d66590f67b886d978acd362eaead8b18c7.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/POWL.png"
  },
  "0x000000000000000000000000000000007bf2fcc61eea41048648bcfbe4066b63": {
    "symbol": "VTI",
    "isin": "US9229087690",
    "contract": "0x0594134df3f171a354d9c85ebd65b7a6148f6d09",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x0594134df3f171a354d9c85ebd65b7a6148f6d09.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/VTI.png"
  },
  "0x000000000000000000000000000000007cacd0cd4c0547d480e89459e648592b": {
    "symbol": "RKLB",
    "isin": "US7731211089",
    "contract": "0x3b14c39e89d60d627b42a1a4ca45b5bb45fc12e2",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x3b14c39e89d60d627b42a1a4ca45b5bb45fc12e2.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/RKLB.png"
  },
  "0x000000000000000000000000000000007dadfeb7f65f49fb9fb1bc94bbbf2d1e": {
    "symbol": "TER",
    "isin": "US8807701029",
    "contract": "0x2778c5024d5ca2cdb0f8ead671ffc69963adcd9c",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x2778c5024d5ca2cdb0f8ead671ffc69963adcd9c.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/TER.png"
  },
  "0x000000000000000000000000000000007f48cdbfaced4ff4b34fbc832f449c33": {
    "symbol": "CRWV",
    "isin": "US21873S1087",
    "contract": "0x5f10a1c971b69e47e059e1dc91901b59b3fb49c3",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x5f10a1c971b69e47e059e1dc91901b59b3fb49c3.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/CRWV.png"
  },
  "0x00000000000000000000000000000000819d5f213b4746e79145d1fc118dcfed": {
    "symbol": "CLS",
    "isin": "CA15101Q2071",
    "contract": "0xbf449977089c718c004a66c554b26b94ef3ad4de",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xbf449977089c718c004a66c554b26b94ef3ad4de.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/CLS.png"
  },
  "0x000000000000000000000000000000008616857c3143457db8534cc7ccac1420": {
    "symbol": "FIG",
    "isin": "US3168411052",
    "contract": "0x41f4267525a8aff329540ef24fd83d9044758b33",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x41f4267525a8aff329540ef24fd83d9044758b33.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/FIG.png"
  },
  "0x0000000000000000000000000000000086aeaac3c7d9422c90f6fd41aff0eaf7": {
    "symbol": "AMD",
    "isin": "US0079031078",
    "contract": "0x86923f96303d656e4aa86d9d42d1e57ad2023fdc",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x86923f96303d656e4aa86d9d42d1e57ad2023fdc.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/AMD.png"
  },
  "0x000000000000000000000000000000008a8710f26e424b2dbc1f6b5de9f267b8": {
    "symbol": "EWY",
    "isin": "US4642867729",
    "contract": "0x7f0abef0c07280f82c6a08ead09ded6bae2c13fc",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x7f0abef0c07280f82c6a08ead09ded6bae2c13fc.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/EWY.png"
  },
  "0x000000000000000000000000000000008a8af3575ac442b8ab46c2d966b2107d": {
    "symbol": "SLS",
    "isin": "US81642T2096",
    "contract": "0x285b231728c7e4333799183df1094d775246a535",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x285b231728c7e4333799183df1094d775246a535.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/SLS.png"
  },
  "0x000000000000000000000000000000008e29de423e4b4105b3644e645fda354a": {
    "symbol": "CRDO",
    "isin": "KYG254571055",
    "contract": "0x4d67253bc223e6b0e104f1084c1fb2b669ddc41b",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x4d67253bc223e6b0e104f1084c1fb2b669ddc41b.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/CRDO.png"
  },
  "0x000000000000000000000000000000008f38b46a1c554840a92533677cce89ad": {
    "symbol": "SMH",
    "isin": "US92189F6768",
    "contract": "0x072f979c2cac8e1391b0162a87fee094bf8744a0",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x072f979c2cac8e1391b0162a87fee094bf8744a0.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/SMH.png"
  },
  "0x000000000000000000000000000000008f9ceee3a7954365bdd32ff0094d9b0f": {
    "symbol": "VRT",
    "isin": "US92537N1081",
    "contract": "0xfa78c12e6488814a0262e4e802749a4a737d5fb7",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xfa78c12e6488814a0262e4e802749a4a737d5fb7.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/VRT.png"
  },
  "0x000000000000000000000000000000008fcfbfc7a42c40d4aa44dc7dc943b078": {
    "symbol": "NOW",
    "isin": "US81762P1021",
    "contract": "0x0c3260af4b8f13a69c4c2dfb84fd667890cdfa14",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x0c3260af4b8f13a69c4c2dfb84fd667890cdfa14.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/NOW.png"
  },
  "0x000000000000000000000000000000008ffcea951a5b43138e764a75e22284f9": {
    "symbol": "XLK",
    "isin": "US81369Y8030",
    "contract": "0x15cd20759ce7f3285c29a319de2d1a2e098c6f43",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x15cd20759ce7f3285c29a319de2d1a2e098c6f43.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/XLK.png"
  },
  "0x000000000000000000000000000000009148f90c74194b4ab333453c9ddaccbf": {
    "symbol": "AMBA",
    "isin": "KYG037AX1015",
    "contract": "0x99d9d8663545151603863c5acbd6fc3218899009",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x99d9d8663545151603863c5acbd6fc3218899009.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/AMBA.png"
  },
  "0x00000000000000000000000000000000915f477416294f5099a5e0e09f327ce5": {
    "symbol": "NVDA",
    "isin": "US67066G1040",
    "contract": "0xd0601ce157db5bdc3162bbac2a2c8af5320d9eec",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xd0601ce157db5bdc3162bbac2a2c8af5320d9eec.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/NVDA.png"
  },
  "0x0000000000000000000000000000000092891cbd0b204f8a87d7878e0fbd60a8": {
    "symbol": "JOBY",
    "isin": "KYG651631007",
    "contract": "0xb334c5ce741b80b5b671f47f5c269cb193fe8e24",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xb334c5ce741b80b5b671f47f5c269cb193fe8e24.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/JOBY.png"
  },
  "0x000000000000000000000000000000009448faa0b7c14ea798a7787a8cb9db5a": {
    "symbol": "QUBT",
    "isin": "US74766W1080",
    "contract": "0x59818904ab4ce163b3ce4ffb64f2d6ca02c434b4",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x59818904ab4ce163b3ce4ffb64f2d6ca02c434b4.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/QUBT.png"
  },
  "0x0000000000000000000000000000000094e84a8622774d5699842d9b0621b871": {
    "symbol": "PWR",
    "isin": "US74762E1029",
    "contract": "0x9ab02ead789b6903c3c44d0ed32f9c707cdf12fd",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x9ab02ead789b6903c3c44d0ed32f9c707cdf12fd.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/PWR.png"
  },
  "0x00000000000000000000000000000000952cedaaab6e4593b884e68a4efbf481": {
    "symbol": "F",
    "isin": "US3453708600",
    "contract": "0x25c288e6d899b9bc30160965ad9644c67e73be0c",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x25c288e6d899b9bc30160965ad9644c67e73be0c.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/F.png"
  },
  "0x00000000000000000000000000000000968cee5f446f422d876c7661ff20b373": {
    "symbol": "ZS",
    "isin": "US98980G1022",
    "contract": "0x7dc013eb55e436f30d7ed1afe4e36d6e45e3c3f7",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x7dc013eb55e436f30d7ed1afe4e36d6e45e3c3f7.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/ZS.png"
  },
  "0x00000000000000000000000000000000970b46dbdece4ae4958a53815e62a85a": {
    "symbol": "COIN",
    "isin": "US19260Q1076",
    "contract": "0x6330d8c3178a418788df01a47479c0ce7ccf450b",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x6330d8c3178a418788df01a47479c0ce7ccf450b.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/COIN.png"
  },
  "0x0000000000000000000000000000000098d983331a5245e98847e635b93eb920": {
    "symbol": "XOM",
    "isin": "US30233Q1085",
    "contract": "0xf9b46d3d1b22199d4d1025a9cedb540a33f1a2d5",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xf9b46d3d1b22199d4d1025a9cedb540a33f1a2d5.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/XOM.png"
  },
  "0x00000000000000000000000000000000994b82ab24514d439fccc87156df35f9": {
    "symbol": "NBIS",
    "isin": "NL0009805522",
    "contract": "0x9d9c6684f596f66a64c030b93a886d51fd4d7931",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x9d9c6684f596f66a64c030b93a886d51fd4d7931.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/NBIS.png"
  },
  "0x00000000000000000000000000000000a12860406ce84eb098e3861878aaf584": {
    "symbol": "SMR",
    "isin": "US67079K1007",
    "contract": "0x1eebee7f74517e0279dfb09d25b0407beec3fdd6",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x1eebee7f74517e0279dfb09d25b0407beec3fdd6.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/SMR.png"
  },
  "0x00000000000000000000000000000000a153b6084a5743dfa4e4264b02cc0737": {
    "symbol": "SOUN",
    "isin": "US8361001071",
    "contract": "0x6e3dfd9f7e1649baa14d25cac18c94d62db10a54",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x6e3dfd9f7e1649baa14d25cac18c94d62db10a54.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/SOUN.png"
  },
  "0x00000000000000000000000000000000a20e50a9f81946d58dafdad20661ec10": {
    "symbol": "POET",
    "isin": "CA73044W3021",
    "contract": "0xcf6b2d875361be807eafa57458c80f28521f9333",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xcf6b2d875361be807eafa57458c80f28521f9333.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/POET.png"
  },
  "0x00000000000000000000000000000000a27266921f864cf2aa69baad4124d11f": {
    "symbol": "APP",
    "isin": "US03831W1080",
    "contract": "0xa249baf1063af884807c1e1400aef7784836917e",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xa249baf1063af884807c1e1400aef7784836917e.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/APP.png"
  },
  "0x00000000000000000000000000000000a47bbc4c8a0b4f44a6d1aff3242b3088": {
    "symbol": "AAOI",
    "isin": "US03823U1025",
    "contract": "0x521cf887e6531c6f667b5bc4d896e5d9bfe8eb2e",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x521cf887e6531c6f667b5bc4d896e5d9bfe8eb2e.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/AAOI.png"
  },
  "0x00000000000000000000000000000000a5fc7b0af972454baf7c124f624ae97f": {
    "symbol": "LITE",
    "isin": "US55024U1097",
    "contract": "0x8ef20885f94e3d9bc7eb3080279188bd5ed7c08c",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x8ef20885f94e3d9bc7eb3080279188bd5ed7c08c.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/LITE.png"
  },
  "0x00000000000000000000000000000000a705ceeae1b44d8981ab86169e52891e": {
    "symbol": "AEIS",
    "isin": "US0079731008",
    "contract": "0xfaf9cb261b5fcc1f404bb10cd39c5c6c1974e612",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xfaf9cb261b5fcc1f404bb10cd39c5c6c1974e612.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/AEIS.png"
  },
  "0x00000000000000000000000000000000a8a9aa61c79e45b48d4ae5e9b6941ed1": {
    "symbol": "KTOS",
    "isin": "US50077B2079",
    "contract": "0x7fd06a4d81ccfa3f351394e144d5191874c31313",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x7fd06a4d81ccfa3f351394e144d5191874c31313.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/KTOS.png"
  },
  "0x00000000000000000000000000000000a98d1e2ff1ea4e54a538680595585902": {
    "symbol": "AMAT",
    "isin": "US0382221051",
    "contract": "0x36046893810a7e7fce501229d57dc3fc8c8716d0",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x36046893810a7e7fce501229d57dc3fc8c8716d0.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/AMAT.png"
  },
  "0x00000000000000000000000000000000aa33b104a54c4de08cc929fb8e8af349": {
    "symbol": "MRVL",
    "isin": "US5738741041",
    "contract": "0x62fd0668e10d8b72339be2dcf7643001688ff13b",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x62fd0668e10d8b72339be2dcf7643001688ff13b.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/MRVL.png"
  },
  "0x00000000000000000000000000000000aad6874f8eba489d8a46fe1cbddc8db4": {
    "symbol": "JBL",
    "isin": "US4663131039",
    "contract": "0xeaf2512dfc1beac608f8794b3793cd4e02894aa6",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xeaf2512dfc1beac608f8794b3793cd4e02894aa6.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/JBL.png"
  },
  "0x00000000000000000000000000000000ac7debdd1135468e9b6a35678f81bb28": {
    "symbol": "LLY",
    "isin": "US5324571083",
    "contract": "0x8005d266423c7ea827372c9c864491e5786600ea",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x8005d266423c7ea827372c9c864491e5786600ea.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/LLY.png"
  },
  "0x00000000000000000000000000000000ad4edb65cfed4b93a3f768502f7ac2b2": {
    "symbol": "QCOM",
    "isin": "US7475251036",
    "contract": "0x0f17206447090e464c277571124dd2688e48aea9",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x0f17206447090e464c277571124dd2688e48aea9.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/QCOM.png"
  },
  "0x00000000000000000000000000000000b1ab18e03806432eac8895cdd289a769": {
    "symbol": "JNJ",
    "isin": "US4781601046",
    "contract": "0x03dfbbe0ac4e7bcdafd08ed41a400326b77d8c80",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x03dfbbe0ac4e7bcdafd08ed41a400326b77d8c80.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/JNJ.png"
  },
  "0x00000000000000000000000000000000b3a0668c099e4767b0be3e33d2542cae": {
    "symbol": "KSS",
    "isin": "US5002551043",
    "contract": "0x12e3c047bf9aecaf9ddc98c05c31bfd1dd043993",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x12e3c047bf9aecaf9ddc98c05c31bfd1dd043993.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/KSS.png"
  },
  "0x00000000000000000000000000000000b423c47a2fed4814a7f1c3c05e1dcb97": {
    "symbol": "ANET",
    "isin": "US0404132054",
    "contract": "0x28babd556b60e53663b8615036479a29c2cdd1bf",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x28babd556b60e53663b8615036479a29c2cdd1bf.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/ANET.png"
  },
  "0x00000000000000000000000000000000b5fe9845954e479aa8519666c3f56476": {
    "symbol": "CTSH",
    "isin": "US1924461023",
    "contract": "0x63d5a3b6939a33f1e75d8bcd85759858239600db",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x63d5a3b6939a33f1e75d8bcd85759858239600db.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/CTSH.png"
  },
  "0x00000000000000000000000000000000b6231b1f45a647a1930f919f161df020": {
    "symbol": "PL",
    "isin": "US72703X1063",
    "contract": "0xaa4d64474c172010ab57719cb9951e6142a100d3",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xaa4d64474c172010ab57719cb9951e6142a100d3.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/PL.png"
  },
  "0x00000000000000000000000000000000b6f6e756b7ff46b88238c18d0bc07fd4": {
    "symbol": "IONQ",
    "isin": "US46222L1089",
    "contract": "0x558378e000d634a36593e338ebacdd6207640efe",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x558378e000d634a36593e338ebacdd6207640efe.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/IONQ.png"
  },
  "0x00000000000000000000000000000000b7d8350ca5a94c8f999bcee3b17c079d": {
    "symbol": "TE",
    "isin": "US35834F1049",
    "contract": "0xb1969f6604ca1ae7a2cd3f1827876e914594ca2d",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xb1969f6604ca1ae7a2cd3f1827876e914594ca2d.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/TE.png"
  },
  "0x00000000000000000000000000000000b82496d605ca4b019c9200daa049f52f": {
    "symbol": "SHOP",
    "isin": "CA82509L1076",
    "contract": "0xf53f66751b1eff985311b693531e3290f600c410",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xf53f66751b1eff985311b693531e3290f600c410.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/SHOP.png"
  },
  "0x00000000000000000000000000000000b9e9686fc0904e87aef5134b823020e3": {
    "symbol": "NVTS",
    "isin": "US63942X1063",
    "contract": "0xbe6702d7b70315376dc48a3293f24f0982f86386",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xbe6702d7b70315376dc48a3293f24f0982f86386.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/NVTS.png"
  },
  "0x00000000000000000000000000000000ba4c7482f4e048f9a8d4a19fa2d1227a": {
    "symbol": "PATH",
    "isin": "US90364P1057",
    "contract": "0xfb2664f07b6aadd29ea7a59d8859b1aeb8645cda",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xfb2664f07b6aadd29ea7a59d8859b1aeb8645cda.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/PATH.png"
  },
  "0x00000000000000000000000000000000bb970311792440cebde7cef41f80d446": {
    "symbol": "UMC",
    "isin": "US9108734057",
    "contract": "0x0e6e67ba88e7b5d9b67636a215c76779b948de79",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x0e6e67ba88e7b5d9b67636a215c76779b948de79.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/UMC.png"
  },
  "0x00000000000000000000000000000000be23b7c535e841a3b1bae97bc0166eae": {
    "symbol": "ORCL",
    "isin": "US68389X1054",
    "contract": "0xb0992820e760d836549ba69bc7598b4af75dee03",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xb0992820e760d836549ba69bc7598b4af75dee03.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/ORCL.png"
  },
  "0x00000000000000000000000000000000bf2748d148654427a4034359cbfb6f91": {
    "symbol": "COHR",
    "isin": "US19247G1076",
    "contract": "0x92f9f459f1a9a5ad266b182be7bffd1c6c666894",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x92f9f459f1a9a5ad266b182be7bffd1c6c666894.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/COHR.png"
  },
  "0x00000000000000000000000000000000bf86ec8b050b46a9a7ad5a532b90c6af": {
    "symbol": "GEV",
    "isin": "US36828A1016",
    "contract": "0x94b8aae43a1ccc08aa64b7d1f29b4d920af4a0c9",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x94b8aae43a1ccc08aa64b7d1f29b4d920af4a0c9.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/GEV.png"
  },
  "0x00000000000000000000000000000000c0c36a7e53234128bcb2e369ebc065c2": {
    "symbol": "AUR",
    "isin": "US0517741072",
    "contract": "0x373c06c4f7bde527d7dae4ba169e42b55e393ced",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x373c06c4f7bde527d7dae4ba169e42b55e393ced.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/AUR.png"
  },
  "0x00000000000000000000000000000000c0d3fe37e35240d1a0f311a07e2b9fc4": {
    "symbol": "IBM",
    "isin": "US4592001014",
    "contract": "0x980dcf6766fa79f5cf0c4aadb3ab477ff15a9619",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x980dcf6766fa79f5cf0c4aadb3ab477ff15a9619.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/IBM.png"
  },
  "0x00000000000000000000000000000000c1fbbd1f58eb47a1a1d16285e48e2f20": {
    "symbol": "NAVN",
    "isin": "US6391931010",
    "contract": "0xf7181b63fdb858558a74ba96bc42732684cd7965",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xf7181b63fdb858558a74ba96bc42732684cd7965.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/NAVN.png"
  },
  "0x00000000000000000000000000000000c23820045b544bafbdc0dd622098fcc3": {
    "symbol": "INOD",
    "isin": "US4576422053",
    "contract": "0xf1953dab6fad537488d5a022361ffaa8b4c95ec6",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xf1953dab6fad537488d5a022361ffaa8b4c95ec6.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/INOD.png"
  },
  "0x00000000000000000000000000000000c2425be3658540dd8e2424cbf3c5c649": {
    "symbol": "AAPL",
    "isin": "US0378331005",
    "contract": "0xaf3d76f1834a1d425780943c99ea8a608f8a93f9",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xaf3d76f1834a1d425780943c99ea8a608f8a93f9.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/AAPL.png"
  },
  "0x00000000000000000000000000000000c40c1aabe3514588ae86defffe868021": {
    "symbol": "AMC",
    "isin": "US00165C3025",
    "contract": "0x05a3d1cd21d0c88145e82600e62e7e496e0f222b",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x05a3d1cd21d0c88145e82600e62e7e496e0f222b.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/AMC.png"
  },
  "0x00000000000000000000000000000000c4aa88df91204d23a4ff43cd0768298d": {
    "symbol": "RIVN",
    "isin": "US76954A1034",
    "contract": "0xb1bf26c1d20ff267a4f93550d1e0d06ac40a114b",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xb1bf26c1d20ff267a4f93550d1e0d06ac40a114b.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/RIVN.png"
  },
  "0x00000000000000000000000000000000c5ac9420091c4f9eb3ff1fdd1d2b60c6": {
    "symbol": "GME",
    "isin": "US36467W1099",
    "contract": "0x1b0e319c6a659f002271b69db8a7df2f911c153e",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x1b0e319c6a659f002271b69db8a7df2f911c153e.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/GME.png"
  },
  "0x00000000000000000000000000000000c5e6650eefa247d599fba9cf6dd5bdad": {
    "symbol": "MPWR",
    "isin": "US6098391054",
    "contract": "0x52d50d0280ad1054b43f052bd70a49a212a1b128",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x52d50d0280ad1054b43f052bd70a49a212a1b128.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/MPWR.png"
  },
  "0x00000000000000000000000000000000c6257ac357d34120ba22c2589ba17cc9": {
    "symbol": "PENG",
    "isin": "US7069151055",
    "contract": "0x9b23573b156b52565012f5ce02cdf60afbaa70be",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x9b23573b156b52565012f5ce02cdf60afbaa70be.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/PENG.png"
  },
  "0x00000000000000000000000000000000c8325c5c9c7e4ca2a3d8344500c5c483": {
    "symbol": "NET",
    "isin": "US18915M1071",
    "contract": "0x116f00968269b7bfbad4109ce591d6e74c0601d4",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x116f00968269b7bfbad4109ce591d6e74c0601d4.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/NET.png"
  },
  "0x00000000000000000000000000000000c89bb960aac6406fb95b34dc136a6016": {
    "symbol": "SCHD",
    "isin": "US8085247976",
    "contract": "0xd63abb2c13d7a8421a8017a712802053568e3c1d",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xd63abb2c13d7a8421a8017a712802053568e3c1d.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/SCHD.png"
  },
  "0x00000000000000000000000000000000c8fa21fd16c744c9ac5c7f5a60ca433a": {
    "symbol": "LUNR",
    "isin": "US46125A1007",
    "contract": "0xa5d4968421ba94814be3b136b15cf422101ac1a3",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xa5d4968421ba94814be3b136b15cf422101ac1a3.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/LUNR.png"
  },
  "0x00000000000000000000000000000000c91ba6e57a1c493f99cae2174e43c86e": {
    "symbol": "MSTR",
    "isin": "US5949724083",
    "contract": "0xec262a75e413fafd0df80480274532c79d42da09",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xec262a75e413fafd0df80480274532c79d42da09.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/MSTR.png"
  },
  "0x00000000000000000000000000000000cbe64873637849e38e848d35826726d4": {
    "symbol": "ABCL",
    "isin": "CA00288U1066",
    "contract": "0x3139d77ace0cbaa5bdfd38bd1f1911a794af0b0e",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x3139d77ace0cbaa5bdfd38bd1f1911a794af0b0e.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/ABCL.png"
  },
  "0x00000000000000000000000000000000cdf9557e169847919e821c0a7bbb4fd3": {
    "symbol": "AXON",
    "isin": "US05464C1018",
    "contract": "0xc27dbd474af5181c5a8777903690d8d262d12648",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xc27dbd474af5181c5a8777903690d8d262d12648.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/AXON.png"
  },
  "0x00000000000000000000000000000000cfece3244ea34bb29414dd9488b32d9f": {
    "symbol": "TSLA",
    "isin": "US88160R1014",
    "contract": "0x322f0929c4625ed5bad873c95208d54e1c003b2d",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x322f0929c4625ed5bad873c95208d54e1c003b2d.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/TSLA.png"
  },
  "0x00000000000000000000000000000000d198f900bc014be980442c67f2444460": {
    "symbol": "EWT",
    "isin": "US46434G7723",
    "contract": "0x1c690498150252222c275a5ced69d3a6b1f52d5e",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x1c690498150252222c275a5ced69d3a6b1f52d5e.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/EWT.png"
  },
  "0x00000000000000000000000000000000d246e0e1cfa541deb327dcae22f1328a": {
    "symbol": "FTNT",
    "isin": "US34959E1091",
    "contract": "0x3fb8976980d486084b2eb4a404bd12e72823958f",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x3fb8976980d486084b2eb4a404bd12e72823958f.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/FTNT.png"
  },
  "0x00000000000000000000000000000000d2ef3f28627f47539db72dbe14c6f1f7": {
    "symbol": "HPE",
    "isin": "US42824C1099",
    "contract": "0x59dd09d4900c2e4b5f75b7c0d4e6796fcc234cb1",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x59dd09d4900c2e4b5f75b7c0d4e6796fcc234cb1.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/HPE.png"
  },
  "0x00000000000000000000000000000000d3de17a58c1a4b58bd07bef4b9123e7f": {
    "symbol": "VSAT",
    "isin": "US92552V1008",
    "contract": "0x26dcbfb34fc83cabd6990f449674efdc6097ff85",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x26dcbfb34fc83cabd6990f449674efdc6097ff85.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/VSAT.png"
  },
  "0x00000000000000000000000000000000d44f85ce72274d6e867a54fa2f4009cb": {
    "symbol": "ADBE",
    "isin": "US00724F1012",
    "contract": "0x232b8ed6377be97813853b0ac104c4cda8378d1b",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x232b8ed6377be97813853b0ac104c4cda8378d1b.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/ADBE.png"
  },
  "0x00000000000000000000000000000000d491ee29c5564368830ce787289dcae8": {
    "symbol": "XNDU",
    "isin": "CA98390R1029",
    "contract": "0xa8eb3bccbf2017ee7cbfb652eb51cf2e1b153289",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xa8eb3bccbf2017ee7cbfb652eb51cf2e1b153289.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/XNDU.png"
  },
  "0x00000000000000000000000000000000d4bd519623e545bf91b7ea590c66801e": {
    "symbol": "DDOG",
    "isin": "US23804L1035",
    "contract": "0x27c99fbde9d0d2aa4f4bfb4943f237843ddf6958",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x27c99fbde9d0d2aa4f4bfb4943f237843ddf6958.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/DDOG.png"
  },
  "0x00000000000000000000000000000000d6571fe1a63748a4b7b79b37612dc9b8": {
    "symbol": "RDW",
    "isin": "US75776W1036",
    "contract": "0x92ef19e82bd8ff36661de838d5eae7e5cef0effe",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x92ef19e82bd8ff36661de838d5eae7e5cef0effe.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/RDW.png"
  },
  "0x00000000000000000000000000000000d73a53e43ed744d4b5d42c5293ba51ad": {
    "symbol": "BE",
    "isin": "US0937121079",
    "contract": "0x822cc93ffd030293e9842c30bbd678f530701867",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x822cc93ffd030293e9842c30bbd678f530701867.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/BE.png"
  },
  "0x00000000000000000000000000000000d8a566c761e845fc8adffb9b2295c3ee": {
    "symbol": "DJT",
    "isin": "US25400Q1058",
    "contract": "0x1d11f0496982706c5e14a514d4e79f2e6bde4516",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x1d11f0496982706c5e14a514d4e79f2e6bde4516.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/DJT.png"
  },
  "0x00000000000000000000000000000000d9dd6bf798564cfd8d65f3f95fc915c3": {
    "symbol": "OKLO",
    "isin": "US02156V1098",
    "contract": "0x8b2f88497f15a18e9d4ffa1a8ffb8538399ae774",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x8b2f88497f15a18e9d4ffa1a8ffb8538399ae774.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/OKLO.png"
  },
  "0x00000000000000000000000000000000da64fede272447a3b772423f0962a984": {
    "symbol": "ZM",
    "isin": "US98980L1017",
    "contract": "0x44c4f142009036cf477ed2d09932051843137cf1",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x44c4f142009036cf477ed2d09932051843137cf1.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/ZM.png"
  },
  "0x00000000000000000000000000000000dceece67a8c740f180047f65ccb4c44a": {
    "symbol": "FLNC",
    "isin": "US34379V1035",
    "contract": "0x282e87451e10fa6679bc7d76c69be44cd3fc777c",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x282e87451e10fa6679bc7d76c69be44cd3fc777c.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/FLNC.png"
  },
  "0x00000000000000000000000000000000df2cb1370b1b41a8b896a706fefae931": {
    "symbol": "SOFI",
    "isin": "US83406F1021",
    "contract": "0x98e75885157c80992a8d41b696d8c9c6fb30a926",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x98e75885157c80992a8d41b696d8c9c6fb30a926.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/SOFI.png"
  },
  "0x00000000000000000000000000000000dfb6ab4d0e0f4cc5bff6d60396755b5f": {
    "symbol": "DOCN",
    "isin": "US25402D1028",
    "contract": "0xc02f12b9fe9e707079ec0d546f3050d3f6c1f8bd",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xc02f12b9fe9e707079ec0d546f3050d3f6c1f8bd.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/DOCN.png"
  },
  "0x00000000000000000000000000000000e0c9d26f234a467fa738e2642dd815ca": {
    "symbol": "WULF",
    "isin": "US88080T1043",
    "contract": "0x348be1a8663f15edde5cdf8a96bb69078f7ab6fd",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x348be1a8663f15edde5cdf8a96bb69078f7ab6fd.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/WULF.png"
  },
  "0x00000000000000000000000000000000e0e44220991548d0b030699e3062c2a7": {
    "symbol": "COST",
    "isin": "US22160K1051",
    "contract": "0x4ea005168d7f09a7a0ba9d1def21a479950e44c2",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x4ea005168d7f09a7a0ba9d1def21a479950e44c2.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/COST.png"
  },
  "0x00000000000000000000000000000000e2f7326613594997b83c3fa7c2e63d70": {
    "symbol": "TTD",
    "isin": "US88339J1051",
    "contract": "0x0b5fb4031cae9163db10b169ee72685f0edc8545",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x0b5fb4031cae9163db10b169ee72685f0edc8545.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/TTD.png"
  },
  "0x00000000000000000000000000000000e42a66686f784f69abb7759114f38f51": {
    "symbol": "HWM",
    "isin": "US4432011082",
    "contract": "0xaea445c5f3db1a462998ccc422a875a361ee5d99",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xaea445c5f3db1a462998ccc422a875a361ee5d99.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/HWM.png"
  },
  "0x00000000000000000000000000000000e56a9632bdd64f7ea0745b48971ad047": {
    "symbol": "HIMS",
    "isin": "US4330001060",
    "contract": "0xccee82fe024c36fa15e1005ede3e9e4787e23d09",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xccee82fe024c36fa15e1005ede3e9e4787e23d09.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/HIMS.png"
  },
  "0x00000000000000000000000000000000e5c73eb672384c37ab8554f9ebe3131a": {
    "symbol": "CELH",
    "isin": "US15118V2079",
    "contract": "0x8cf07c5a878945185d327aaa6e33faa95f95e7bf",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x8cf07c5a878945185d327aaa6e33faa95f95e7bf.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/CELH.png"
  },
  "0x00000000000000000000000000000000eed533334fe84ce08253d5b1dcff7373": {
    "symbol": "RBLX",
    "isin": "US7710491033",
    "contract": "0xf0c4bf4c582cb3836e98394b1d4e7b7281101be8",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xf0c4bf4c582cb3836e98394b1d4e7b7281101be8.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/RBLX.png"
  },
  "0x00000000000000000000000000000000eefd305f8fd04bdb882b158b0b30d776": {
    "symbol": "SPMO",
    "isin": "US46138E3392",
    "contract": "0xad622320e520de39e72d41ef07438c3fd3354875",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xad622320e520de39e72d41ef07438c3fd3354875.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/SPMO.png"
  },
  "0x00000000000000000000000000000000efb0e40b668c4b92876632971560e252": {
    "symbol": "AMKR",
    "isin": "US0316521006",
    "contract": "0xdd356aa38f40a7b7076755ac854b6fbb1f0d305b",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xdd356aa38f40a7b7076755ac854b6fbb1f0d305b.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/AMKR.png"
  },
  "0x00000000000000000000000000000000f1469aaabbf6434cb8820d1026a28460": {
    "symbol": "ONTO",
    "isin": "US6833441057",
    "contract": "0x8ff63eaeee3fe54ba450c4f5538064ec5a893aef",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x8ff63eaeee3fe54ba450c4f5538064ec5a893aef.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/ONTO.png"
  },
  "0x00000000000000000000000000000000f379ff90efb54b4cb6280fc923e38926": {
    "symbol": "SOXX",
    "isin": "US4642875235",
    "contract": "0x75742c18bc1f1c5c5f448f4c9d9c6f66dafaaa38",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x75742c18bc1f1c5c5f448f4c9d9c6f66dafaaa38.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/SOXX.png"
  },
  "0x00000000000000000000000000000000f6e013126d524981acc9df053b0c19ea": {
    "symbol": "ASML",
    "isin": "USN070592100",
    "contract": "0x47f93d52cbec7c6d2cfc080e154002370a60daea",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x47f93d52cbec7c6d2cfc080e154002370a60daea.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/ASML.png"
  },
  "0x00000000000000000000000000000000f759d1fccdb8418a8970ee6932aa6584": {
    "symbol": "HII",
    "isin": "US4464131063",
    "contract": "0xeb61c0ed490a367d4e3631ccf8a74b3bfc7e775d",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xeb61c0ed490a367d4e3631ccf8a74b3bfc7e775d.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/HII.png"
  },
  "0x00000000000000000000000000000000f7eed20de20b4d05851c48313f1701b7": {
    "symbol": "AVAV",
    "isin": "US0080731088",
    "contract": "0xf6290b5e7c26502e2da514c31509849718ea76a5",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xf6290b5e7c26502e2da514c31509849718ea76a5.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/AVAV.png"
  },
  "0x00000000000000000000000000000000f87aee91942940d0acf2e657187332af": {
    "symbol": "INDA",
    "isin": "US46429B5984",
    "contract": "0xacef2e09adb47ad6abebad9ff06689e60615c2b6",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xacef2e09adb47ad6abebad9ff06689e60615c2b6.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/INDA.png"
  },
  "0x00000000000000000000000000000000f92f60961bbe43028a23a3d52174b320": {
    "symbol": "MDB",
    "isin": "US60937P1066",
    "contract": "0xddf2266b79abf0b48898959b0ed6e6adf512be74",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xddf2266b79abf0b48898959b0ed6e6adf512be74.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/MDB.png"
  },
  "0x00000000000000000000000000000000f9809b86bf2c492dadb606c7871465a4": {
    "symbol": "QNT",
    "isin": "US74768A1043",
    "contract": "0xb7edfe2f33c1ac06830a971dfb559bde8a2a3d76",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0xb7edfe2f33c1ac06830a971dfb559bde8a2a3d76.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/QNT.png"
  },
  "0x00000000000000000000000000000000fb3ae5faa7f7465a95815518c4cd7a8b": {
    "symbol": "PFE",
    "isin": "US7170811035",
    "contract": "0x7066a64c24e4206cd62e83bf198c1e7eb361f51e",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x7066a64c24e4206cd62e83bf198c1e7eb361f51e.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/PFE.png"
  },
  "0x00000000000000000000000000000000fb93cee635bd469e8dc9849f12f889aa": {
    "symbol": "KLAC",
    "isin": "US4824801009",
    "contract": "0x96b933c74ecb4a0926b9210cef7b743ef46be2e9",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x96b933c74ecb4a0926b9210cef7b743ef46be2e9.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/KLAC.png"
  },
  "0x00000000000000000000000000000000ff5617943e99488d87281bae9b706d53": {
    "symbol": "IBRX",
    "isin": "US45256X1037",
    "contract": "0x7c148f74ac7445d1f28366b7fcdc6792a9fcd0cf",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x7c148f74ac7445d1f28366b7fcdc6792a9fcd0cf.png",
    "companyLogoUrl": "https://financialmodelingprep.com/image-stock/IBRX.png"
  },
  "0x0000000000000000000000000000000036e64f28008640f9ab10ed949faf23b2": {
    "symbol": "TSEM",
    "isin": "IL0010823792",
    "contract": "0x89776d4cd68193597a2fc132cfac1fde36ccea8a",
    "issuerLogoUrl": "https://cdn.robinhood.com/ncw_assets/logos/0x89776d4cd68193597a2fc132cfac1fde36ccea8a.png",
    "companyLogoUrl": "https://towersemi.com/wp-content/uploads/2020/02/cropped-Group-221-1-192x192.png"
  }
};
