/**
 * Documentos legales de Ecomerxio POS (Colombia).
 *
 * Al cambiar algo de fondo: subir LEGAL_VERSION (y LegalService.VERSION en el backend, deben coincidir). Todos los
 * usuarios vuelven a ver y aceptar los documentos la próxima vez que entren.
 *
 * Los datos entre corchetes del TITULAR son los únicos que faltan: se llenan aquí una sola vez y salen en todos los
 * documentos. Borrador preparado para revisión de un abogado colombiano antes de salir a vender.
 */

export const LEGAL_VERSION = '2026-10-21'
export const LEGAL_DATE = '21 de octubre de 2026'

/** Quién es el dueño del software y responsable del tratamiento. */
export const TITULAR = {
  nombre: 'Diego Armando Parada Cañón',
  tipo: 'persona natural',
  documento: 'cédula de ciudadanía n.º 1.032.407.794',
  domicilio: 'Bogotá D.C., Colombia',
  direccion: '[DIRECCIÓN DE NOTIFICACIONES]',
  marca: 'Ecomerxio',
  producto: 'Ecomerxio POS',
  correo: 'ecomerxio@gmail.com',
  correoDatos: 'ecomerxio@gmail.com',
  whatsapp: '+57 305 822 5928',
  web: 'https://www.ecomerxio.com',
  app: 'https://pos.ecomerxio.com',
  /** Horario de soporte que se publica en la Política de Servicio. */
  horarioSoporte: 'lunes a sábado de 8:00 a. m. a 6:00 p. m. (hora de Colombia), excepto festivos',
  /** Ciudad cuyos jueces conocen de los conflictos. */
  jurisdiccion: 'Bogotá D.C.',
}

/** Un bloque es un párrafo, o una lista si es un arreglo. Un párrafo que empieza con "!" se destaca (cláusula importante). */
export type LegalBlock = string | string[]

export interface LegalSection {
  title: string
  blocks: LegalBlock[]
}

export type LegalKey = 'TERMINOS' | 'LICENCIA' | 'SERVICIO' | 'FACTURACION' | 'ENCARGO' | 'PRIVACIDAD'

export interface LegalDoc {
  key: LegalKey
  slug: string
  title: string
  /** Para la lista y la casilla de aceptación. */
  short: string
  /** Resumen en lenguaje sencillo (no reemplaza el texto). */
  summary: string[]
  sections: LegalSection[]
}

const T = TITULAR
const ID = `${T.nombre}, ${T.tipo} identificado con ${T.documento}, domiciliado en ${T.domicilio}, quien actúa bajo la marca ${T.marca} (en adelante, "${T.marca.toUpperCase()}" o "EL TITULAR")`

// ============================================================================================ TÉRMINOS

const TERMINOS: LegalDoc = {
  key: 'TERMINOS',
  slug: 'terminos',
  title: 'Términos y Condiciones de Uso',
  short: 'Términos y condiciones',
  summary: [
    'Es un contrato entre tu negocio y Ecomerxio para usar el software por suscripción. No compras el software: lo usas mientras pagues.',
    'Tus datos (productos, ventas, clientes) son tuyos y los puedes descargar cuando quieras.',
    'Tú respondes por tus impuestos, tu facturación ante la DIAN y lo que registres en el sistema.',
    'Si no pagas, el servicio se suspende; si cancelas, tienes 30 días para descargar tu información.',
    'La responsabilidad de Ecomerxio tiene un tope y no cubre pérdidas indirectas (ver "Servicio y responsabilidad").',
  ],
  sections: [
    {
      title: '1. Partes y aceptación',
      blocks: [
        `Estos Términos y Condiciones (los "Términos") regulan el uso de ${T.producto} y de los demás servicios de la plataforma ${T.marca} (en conjunto, "LA PLATAFORMA"), de propiedad de ${ID}.`,
        'EL CLIENTE es la persona natural o jurídica que crea una cuenta para su negocio. Quien hace el registro declara que tiene facultad para obligar al negocio y que es mayor de edad.',
        'Al marcar la casilla de aceptación y crear la cuenta, EL CLIENTE celebra un contrato con EL TITULAR por medios electrónicos, válido y obligatorio en los términos de la Ley 527 de 1999. LA PLATAFORMA guarda prueba de la aceptación (fecha, hora, versión, usuario, dirección IP y navegador).',
        'Forman parte de este contrato, y se aceptan junto con él: (i) la Licencia de Uso y Propiedad Intelectual, (ii) la Política de Servicio y Limitación de Responsabilidad, (iii) el Anexo de Facturación Electrónica, (iv) el Acuerdo de Encargo del Tratamiento de Datos y (v) la Política de Tratamiento de Datos Personales. Si hay contradicción, prevalece el documento especial sobre estos Términos.',
        '!SI NO ESTÁS DE ACUERDO CON ESTOS TÉRMINOS, NO CREES LA CUENTA NI USES LA PLATAFORMA.',
      ],
    },
    {
      title: '2. Relación entre empresarios',
      blocks: [
        'EL CLIENTE contrata LA PLATAFORMA para su actividad comercial o profesional: administrar ventas, inventario, caja, clientes y demás operaciones de su negocio. Por eso declara que no actúa como consumidor final en los términos del artículo 5 de la Ley 1480 de 2011 (Estatuto del Consumidor), y que la relación se rige por el Código de Comercio, el Código Civil y este contrato.',
        'Si una autoridad llegara a considerar que alguna disposición del Estatuto del Consumidor es aplicable, esa disposición se aplicará solo en lo que sea imperativo, y el resto del contrato conservará su validez.',
      ],
    },
    {
      title: '3. Qué es el servicio',
      blocks: [
        'LA PLATAFORMA es un software como servicio (SaaS) al que se accede por internet: punto de venta, caja y turnos, inventario, compras, cotizaciones y prefacturas, cuentas por cobrar, analítica, contabilidad básica, tienda en línea y las demás funciones que estén disponibles según el plan contratado.',
        'Las funciones pueden mejorar, cambiar o retirarse con el tiempo. Si se retira una función esencial del plan contratado, se avisará con al menos quince (15) días de anticipación y EL CLIENTE podrá cancelar sin penalidad.',
        'LA PLATAFORMA es una herramienta de registro y gestión. No presta asesoría contable, tributaria, legal ni financiera, y las cifras, informes, sugerencias y respuestas del asistente con inteligencia artificial son apoyos que EL CLIENTE debe revisar antes de tomar decisiones.',
      ],
    },
    {
      title: '4. Cuenta, usuarios y seguridad',
      blocks: [
        'EL CLIENTE entrega información veraz y la mantiene actualizada. El usuario que crea la cuenta queda como "dueño" y puede crear usuarios para sus empleados con los roles y permisos que decida.',
        'EL CLIENTE es responsable de todo lo que se haga con las cuentas de su negocio, de guardar en secreto contraseñas y PIN, de dar a cada empleado solo los permisos que necesita y de desactivar a quien ya no trabaje con él. Debe avisar de inmediato a EL TITULAR si sospecha un acceso no autorizado.',
        'EL TITULAR podrá suspender un usuario o una cuenta cuando detecte un riesgo de seguridad, fraude o uso contrario a estos Términos, informando a EL CLIENTE.',
      ],
    },
    {
      title: '5. Planes, prueba gratis y pagos',
      blocks: [
        'Los planes, sus precios y lo que incluye cada uno se publican en LA PLATAFORMA. Los precios se expresan en pesos colombianos y, cuando corresponda, se les sumará el IVA u otros impuestos.',
        'Las cuentas nuevas tienen un periodo de prueba gratuito de quince (15) días. Terminada la prueba, para seguir usando LA PLATAFORMA EL CLIENTE debe pagar el plan elegido.',
        'La suscripción se paga por periodos anticipados (mensual o anual). El pago anual tiene el beneficio que se publique al momento de contratarlo. Los pagos hechos no son reembolsables, salvo lo previsto en la sección 3 y en los casos en que la ley lo ordene.',
        'Si un pago no se recibe en la fecha de corte, EL TITULAR podrá, después de avisar por correo o en LA PLATAFORMA y de dar un plazo de cinco (5) días calendario: (i) dejar la cuenta en modo de solo lectura y (ii) luego suspenderla. La información no se borra mientras dure la suspensión, dentro del plazo de la sección 11.',
        'EL TITULAR puede cambiar los precios avisando con al menos treinta (30) días de anticipación. El nuevo precio se aplica desde el siguiente periodo; si EL CLIENTE no está de acuerdo, puede cancelar antes de que empiece.',
      ],
    },
    {
      title: '6. Obligaciones de EL CLIENTE',
      blocks: [
        'EL CLIENTE se obliga a:',
        [
          'Usar LA PLATAFORMA de forma lícita y solo para su propio negocio.',
          'Cumplir sus obligaciones tributarias, contables, comerciales, laborales y de protección al consumidor. En particular, EL CLIENTE es el único responsable de expedir la factura electrónica de venta o el documento equivalente cuando la ley lo exija, de la veracidad de lo que factura y de conservar sus soportes por el tiempo legal.',
          'Revisar la información que registra (precios, impuestos, datos de sus clientes, cantidades) y los documentos que emite. Las cotizaciones y prefacturas son documentos de EL CLIENTE; la prefactura es una revisión previa y no es factura.',
          'Obtener de sus propios clientes, proveedores y empleados las autorizaciones necesarias para tratar sus datos personales en LA PLATAFORMA (ver el Acuerdo de Encargo del Tratamiento de Datos).',
          'Descargar periódicamente copias de su información (LA PLATAFORMA permite exportarla) y tener un plan de contingencia para vender y facturar si no hay internet, luz o servicio.',
          'Pagar oportunamente la suscripción.',
        ],
      ],
    },
    {
      title: '7. Usos prohibidos',
      blocks: [
        'Está prohibido: (a) copiar, vender, sublicenciar, alquilar o permitir el uso de LA PLATAFORMA a terceros ajenos al negocio; (b) descompilar, hacer ingeniería inversa o intentar obtener el código fuente; (c) probar, escanear o vulnerar la seguridad, o acceder a datos de otros clientes; (d) sobrecargar el servicio con programas automáticos no autorizados; (e) usar LA PLATAFORMA para actividades ilícitas, para lavar activos, para evadir impuestos o para vender bienes prohibidos; (f) cargar virus o código malicioso; (g) usar LA PLATAFORMA para construir un producto competidor.',
        'El incumplimiento autoriza a EL TITULAR a suspender o terminar la cuenta de inmediato, sin perjuicio de las acciones legales que procedan.',
      ],
    },
    {
      title: '8. La información de EL CLIENTE',
      blocks: [
        'Toda la información que EL CLIENTE registra (productos, precios, ventas, clientes, proveedores, documentos) es de EL CLIENTE. EL TITULAR no la vende ni la usa para fines distintos de prestar, mantener, asegurar y mejorar el servicio.',
        'EL CLIENTE autoriza a EL TITULAR a usar datos agregados y anonimizados (que no identifican al negocio ni a ninguna persona) para estadísticas y para mejorar LA PLATAFORMA.',
        'EL TITULAR hace copias de seguridad automáticas, pero ellas son una medida de continuidad del servicio y no reemplazan las copias que EL CLIENTE debe descargar.',
      ],
    },
    {
      title: '9. Servicios de terceros',
      blocks: [
        'LA PLATAFORMA funciona sobre servicios de terceros (alojamiento en la nube, base de datos, correo, mensajería, pasarelas de pago, proveedores tecnológicos de facturación electrónica y proveedores de inteligencia artificial, entre otros). Cuando EL CLIENTE activa una integración, acepta además las condiciones de ese tercero.',
        '!EN LA MEDIDA EN QUE LA LEY LO PERMITA, EL TITULAR NO RESPONDE POR LAS FALLAS, DEMORAS, CAMBIOS DE CONDICIONES, RECHAZOS O INCUMPLIMIENTOS DE ESOS TERCEROS (INCLUIDOS LA DIAN, LOS BANCOS, LAS PASARELAS Y LOS PROVEEDORES DE FACTURACIÓN), NI POR LOS COBROS QUE ELLOS HAGAN DIRECTAMENTE A EL CLIENTE.',
      ],
    },
    {
      title: '10. Responsabilidad e indemnidad',
      blocks: [
        'La disponibilidad del servicio, sus límites y la responsabilidad de EL TITULAR se regulan en la Política de Servicio y Limitación de Responsabilidad, que hace parte de este contrato.',
        'EL CLIENTE mantendrá indemne a EL TITULAR frente a reclamos, multas, sanciones, condenas y gastos (incluidos honorarios razonables de abogados) que se originen en: el contenido o los datos que EL CLIENTE registre, el incumplimiento de sus obligaciones legales (tributarias, de facturación, de protección de datos o de consumo), el uso indebido de LA PLATAFORMA por EL CLIENTE o sus usuarios, o el incumplimiento de estos Términos.',
      ],
    },
    {
      title: '11. Duración, cancelación y devolución de la información',
      blocks: [
        'El contrato dura mientras la cuenta esté activa. EL CLIENTE puede cancelarlo en cualquier momento; la cancelación produce efectos al terminar el periodo ya pagado, sin devolución de lo pagado.',
        'EL TITULAR puede terminarlo: (a) por incumplimiento de EL CLIENTE, con efecto inmediato en los casos de la sección 7, o (b) sin causa, avisando con treinta (30) días de anticipación y devolviendo la parte no usada del periodo pagado.',
        'Terminado el contrato o suspendida la cuenta por falta de pago, EL CLIENTE tendrá treinta (30) días calendario para descargar su información. Vencido ese plazo, EL TITULAR podrá eliminarla de forma definitiva, salvo la que deba conservar por ley y la información de prueba de aceptaciones y pagos.',
      ],
    },
    {
      title: '12. Cambios a estos Términos',
      blocks: [
        'EL TITULAR puede modificar estos Términos. Los cambios importantes se avisarán con al menos quince (15) días de anticipación y, al entrar, LA PLATAFORMA pedirá aceptar la nueva versión. Si EL CLIENTE no la acepta, puede cancelar sin penalidad; seguir usando LA PLATAFORMA después de la fecha de vigencia equivale a aceptarla.',
      ],
    },
    {
      title: '13. Avisos',
      blocks: [
        `Los avisos a EL CLIENTE se envían al correo del dueño de la cuenta o se muestran dentro de LA PLATAFORMA. Los avisos a EL TITULAR se envían a ${T.correo}. Las comunicaciones por estos medios son válidas (Ley 527 de 1999).`,
      ],
    },
    {
      title: '14. Ley aplicable y solución de conflictos',
      blocks: [
        'Este contrato se rige por las leyes de la República de Colombia.',
        `Ante cualquier diferencia, las partes buscarán primero un arreglo directo durante treinta (30) días calendario desde el aviso escrito de una de ellas. Si no se logra, podrán acudir a conciliación en un centro autorizado y, en su defecto, a los jueces de ${T.jurisdiccion}, Colombia.`,
      ],
    },
    {
      title: '15. Disposiciones finales',
      blocks: [
        'Si alguna cláusula resulta inválida, las demás conservan su validez. Que EL TITULAR no exija un derecho en un momento dado no significa que renuncie a él. EL CLIENTE no puede ceder este contrato sin autorización escrita de EL TITULAR; EL TITULAR podrá cederlo a una sociedad que controle o en la que aporte LA PLATAFORMA, avisando a EL CLIENTE.',
      ],
    },
  ],
}

// ============================================================================================ LICENCIA

const LICENCIA: LegalDoc = {
  key: 'LICENCIA',
  slug: 'licencia',
  title: 'Licencia de Uso y Propiedad Intelectual',
  short: 'Licencia de uso y propiedad intelectual',
  summary: [
    `El software, su código, diseño, marca y documentación son de ${T.nombre}.`,
    'Tú recibes un permiso de uso limitado, temporal y no exclusivo mientras tengas la suscripción al día. No es una venta ni una cesión.',
    'No puedes copiar, revender, descompilar ni hacer un producto parecido con él.',
  ],
  sections: [
    {
      title: '1. Titularidad',
      blocks: [
        `${T.producto}, la plataforma ${T.marca} y todos sus componentes —código fuente y código objeto, bases de datos y su estructura, arquitectura, algoritmos, interfaces, diseños, pantallas, textos, gráficos, íconos, documentación, manuales, recorridos guiados, plantillas, y sus versiones, actualizaciones y obras derivadas— (en conjunto, "EL SOFTWARE") son de propiedad exclusiva de ${ID}.`,
        'EL SOFTWARE está protegido como obra literaria por la Ley 23 de 1982, la Decisión Andina 351 de 1993, el Decreto 1360 de 1989 y los tratados internacionales sobre derecho de autor, así como por las normas de propiedad industrial y de competencia desleal (Ley 256 de 1996). La violación de estos derechos puede constituir delito (artículos 270 a 272 del Código Penal).',
        `La marca ${T.marca}, sus logotipos, nombres comerciales y signos distintivos son de EL TITULAR. Nada en este documento autoriza su uso por EL CLIENTE, salvo para identificar que usa LA PLATAFORMA.`,
      ],
    },
    {
      title: '2. Qué se le concede a EL CLIENTE',
      blocks: [
        '!EL SOFTWARE NO SE VENDE: SE LICENCIA. EL TITULAR concede a EL CLIENTE una licencia de uso:',
        [
          'No exclusiva: EL TITULAR puede licenciar EL SOFTWARE a cualquier otra persona.',
          'Intransferible y no sublicenciable: EL CLIENTE no puede cederla, venderla, prestarla ni permitir su uso por terceros ajenos a su negocio.',
          'Temporal y revocable: dura mientras la suscripción esté vigente y al día, y termina con el contrato.',
          'Limitada: solo para gestionar el propio negocio de EL CLIENTE, por medio de internet, con el número de usuarios, cajas y funciones del plan contratado.',
          'Onerosa: sujeta al pago de la suscripción.',
          'Territorial: para usarse desde Colombia o desde los países en que LA PLATAFORMA esté disponible.',
        ],
        'La licencia no transfiere a EL CLIENTE ningún derecho patrimonial de autor (reproducción, transformación, distribución, comunicación pública), ni derecho sobre el código fuente, ni ningún otro derecho que no esté expresamente concedido aquí.',
      ],
    },
    {
      title: '3. Restricciones',
      blocks: [
        'EL CLIENTE no podrá, directamente ni por medio de otros:',
        [
          'Copiar, reproducir, modificar, traducir, adaptar o crear obras derivadas de EL SOFTWARE.',
          'Descompilar, desensamblar, hacer ingeniería inversa o intentar descubrir el código fuente, los algoritmos o la estructura de las bases de datos, salvo en los casos excepcionales que la ley permita expresamente.',
          'Retirar, ocultar o alterar avisos de derechos, marcas o leyendas de propiedad.',
          'Extraer de forma masiva o automatizada información de EL SOFTWARE distinta de la propia información de EL CLIENTE.',
          'Usar EL SOFTWARE, sus pantallas o su documentación para desarrollar, entrenar o comercializar un producto o servicio que compita con él.',
          'Eludir las medidas tecnológicas de protección, los límites del plan o los controles de acceso.',
        ],
      ],
    },
    {
      title: '4. Sugerencias y mejoras',
      blocks: [
        'Si EL CLIENTE propone ideas, sugerencias o mejoras, EL TITULAR podrá usarlas libremente y sin pago alguno, y las mejoras que EL TITULAR desarrolle a partir de ellas serán de su propiedad exclusiva. Esto no le da a EL TITULAR ningún derecho sobre la información del negocio de EL CLIENTE.',
      ],
    },
    {
      title: '5. Desarrollos a la medida',
      blocks: [
        'Los ajustes, módulos o integraciones que EL TITULAR desarrolle a pedido de un cliente (por ejemplo, en el plan Enterprise) son propiedad de EL TITULAR y se incorporan a la licencia de ese cliente, salvo que un contrato escrito diga expresamente otra cosa.',
      ],
    },
    {
      title: '6. La información de EL CLIENTE',
      blocks: [
        'La licencia no le da a EL TITULAR la propiedad de la información que EL CLIENTE registra: esa información es y sigue siendo de EL CLIENTE (ver Términos, sección 8).',
      ],
    },
    {
      title: '7. Consecuencias del incumplimiento',
      blocks: [
        'Cualquier uso de EL SOFTWARE por fuera de esta licencia la termina automáticamente, autoriza la suspensión inmediata de la cuenta y faculta a EL TITULAR para ejercer las acciones civiles, penales y administrativas que correspondan, incluido el cobro de los perjuicios causados.',
      ],
    },
  ],
}

// ============================================================================================ SERVICIO

const SERVICIO: LegalDoc = {
  key: 'SERVICIO',
  slug: 'servicio-y-responsabilidad',
  title: 'Política de Servicio y Limitación de Responsabilidad',
  short: 'Servicio y limitación de responsabilidad',
  summary: [
    'Trabajamos para que el servicio esté disponible siempre, pero ningún servicio en internet puede garantizarlo: puede haber caídas.',
    'Si el servicio se cae, Ecomerxio no paga lo que dejaste de vender ni otros perjuicios indirectos.',
    'En todo caso, lo máximo que Ecomerxio respondería es lo que pagaste en los últimos 3 meses.',
    'Si en un mes el servicio se cae más de unas 14 horas por una falla nuestra, te compensamos con 1 a 3 días gratis (lo pides por correo). Las fallas de terceros, como la nube o la DIAN, no cuentan.',
    'Ten siempre un plan B para vender y facturar (la caja sin internet ayuda, pero no lo cubre todo).',
    `Soporte por WhatsApp y correo, ${TITULAR.horarioSoporte}. Si no puedes vender por una falla nuestra, te respondemos en máximo 2 horas.`,
    'Cada noche se hace una copia de seguridad; aun así, descarga tus datos de vez en cuando.',
  ],
  sections: [
    {
      title: '1. El servicio se presta "tal como está" y "según disponibilidad"',
      blocks: [
        `${T.marca} se esfuerza por mantener LA PLATAFORMA funcionando, segura y actualizada, con un objetivo de disponibilidad mensual del noventa y nueve por ciento (99 %). Ese objetivo es una meta de calidad, no una garantía.`,
        '!LA PLATAFORMA SE PRESTA "TAL COMO ESTÁ" Y "SEGÚN DISPONIBILIDAD". EN LA MEDIDA EN QUE LA LEY LO PERMITE, EL TITULAR NO GARANTIZA QUE EL SERVICIO SEA ININTERRUMPIDO, OPORTUNO, LIBRE DE ERRORES O COMPATIBLE CON TODOS LOS EQUIPOS, NI QUE SUS RESULTADOS SEAN APTOS PARA UN FIN PARTICULAR DE EL CLIENTE.',
        'La obligación de EL TITULAR es de medio y no de resultado: poner la diligencia profesional razonable para prestar el servicio.',
      ],
    },
    {
      title: '2. Interrupciones',
      blocks: [
        'El servicio puede interrumpirse o funcionar lento, entre otras razones, por:',
        [
          'Mantenimiento programado (se procurará hacerlo en horas de poca venta y avisar con anticipación) o mantenimiento urgente por seguridad.',
          'Fallas de los proveedores de nube, base de datos, internet, energía, correo, mensajería, pasarelas de pago, proveedores de facturación electrónica o de la DIAN.',
          'Fallas del internet, la energía, los equipos, las impresoras, los lectores o el navegador de EL CLIENTE.',
          'Ataques informáticos, actos de terceros, fuerza mayor o caso fortuito (artículo 64 del Código Civil), incluidas decisiones de autoridad.',
        ],
        'LA PLATAFORMA ofrece una función de venta sin internet que guarda las operaciones en el equipo y las envía cuando vuelve la conexión. Es una ayuda de contingencia con límites (no todas las funciones operan sin conexión y el equipo debe estar en buen estado), no una garantía de continuidad.',
      ],
    },
    {
      title: '3. Qué debe hacer EL CLIENTE',
      blocks: [
        'Como comerciante, EL CLIENTE debe tener un plan de contingencia propio para vender y cumplir sus obligaciones cuando LA PLATAFORMA no esté disponible: por ejemplo, registro manual de ventas, el procedimiento de contingencia de facturación que exija la DIAN, y copias recientes de su información descargadas desde LA PLATAFORMA.',
        'EL CLIENTE debe revisar los datos que registra y los documentos que emite, y reportar a tiempo cualquier error que encuentre.',
      ],
    },
    {
      title: '4. Exclusión de perjuicios indirectos',
      blocks: [
        '!SIN PERJUICIO DE LO PREVISTO PARA DOLO O CULPA GRAVE EN LA SECCIÓN 5, Y EN LA MEDIDA EN QUE LA LEY LO PERMITA, EL TITULAR NO RESPONDERÁ POR LUCRO CESANTE, VENTAS O UTILIDADES DEJADAS DE PERCIBIR, PÉRDIDA DE CLIENTES, DE OPORTUNIDADES O DE BUEN NOMBRE, DAÑOS INDIRECTOS, CONSECUENCIALES, ESPECIALES O PUNITIVOS, NI POR MULTAS, SANCIONES, INTERESES O IMPUESTOS IMPUESTOS A EL CLIENTE POR CUALQUIER AUTORIDAD (INCLUIDA LA DIAN), AUNQUE SE HUBIERA ADVERTIDO SU POSIBILIDAD.',
        '!CON LOS MISMOS LÍMITES, TAMPOCO RESPONDERÁ POR: (A) INTERRUPCIONES O FALLAS DESCRITAS EN LA SECCIÓN 2, SALVO LA COMPENSACIÓN DE LA SECCIÓN 6; (B) ERRORES EN LA INFORMACIÓN QUE REGISTRE EL CLIENTE O SUS USUARIOS (PRECIOS, IMPUESTOS, CANTIDADES, DATOS DE CLIENTES), SALVO QUE SE DEBAN A UNA FALLA DEL SOFTWARE IMPUTABLE A EL TITULAR; (C) DECISIONES TOMADAS CON BASE EN INFORMES, SUGERENCIAS O RESPUESTAS DEL ASISTENTE CON INTELIGENCIA ARTIFICIAL; (D) PÉRDIDAS DE INFORMACIÓN QUE NO PUEDAN RECUPERARSE DE LA ÚLTIMA COPIA DE SEGURIDAD DISPONIBLE; (E) ACCESOS NO AUTORIZADOS CAUSADOS POR EL MAL MANEJO DE CONTRASEÑAS O PERMISOS POR PARTE DE EL CLIENTE; (F) ACTOS U OMISIONES DE TERCEROS PROVEEDORES.',
      ],
    },
    {
      title: '5. Tope máximo de responsabilidad',
      blocks: [
        '!SALVO DOLO O CULPA GRAVE, LA RESPONSABILIDAD TOTAL Y ACUMULADA DE EL TITULAR FRENTE A EL CLIENTE, POR CUALQUIER CAUSA Y POR TODOS LOS HECHOS RELACIONADOS CON LA PLATAFORMA, NO EXCEDERÁ EL VALOR EFECTIVAMENTE PAGADO POR EL CLIENTE POR LA SUSCRIPCIÓN DURANTE LOS TRES (3) MESES ANTERIORES AL HECHO QUE ORIGINÓ EL RECLAMO. DURANTE LA PRUEBA GRATUITA, ESE VALOR ES CERO.',
        'Este tope y las exclusiones anteriores son elementos esenciales del precio: sin ellos el servicio no podría ofrecerse a ese valor.',
        'Conforme al artículo 1522 del Código Civil, nada de lo anterior limita la responsabilidad de EL TITULAR por dolo o culpa grave, ni los derechos que la ley no permita renunciar.',
      ],
    },
    {
      title: '6. Compensación por interrupciones',
      blocks: [
        'Esta compensación aplica solo a las interrupciones imputables a EL TITULAR: errores del software, actualizaciones o cambios de configuración hechos por él. La disponibilidad se mide por mes calendario, según el monitoreo de EL TITULAR, contando únicamente esas interrupciones.',
        'Si en un mes, por esas causas, la disponibilidad es inferior al noventa y ocho por ciento (98 %), EL CLIENTE recibirá días adicionales de servicio, que se suman a su siguiente periodo pagado:',
        [
          'Entre 95 % y menos de 98 % (aproximadamente entre 14 y 36 horas sin servicio en el mes): un (1) día.',
          'Entre 90 % y menos de 95 % (aproximadamente entre 36 y 72 horas): dos (2) días.',
          'Menos de 90 % (más de 3 días): tres (3) días, que es el máximo por mes.',
        ],
        `Para recibirla, EL CLIENTE debe pedirla por escrito a ${T.correo} dentro de los treinta (30) días calendario siguientes al mes afectado.`,
        'No se cuentan como tiempo sin servicio: los mantenimientos programados avisados con al menos veinticuatro (24) horas de anticipación; las fallas del internet, la energía o los equipos de EL CLIENTE; las fallas de terceros, incluidos los proveedores de nube, alojamiento y base de datos, la DIAN, los proveedores de facturación electrónica, los bancos y las pasarelas de pago; los ataques informáticos y la fuerza mayor; las interrupciones causadas por uso indebido; ni la suspensión por falta de pago.',
        'La compensación no aplica durante la prueba gratuita y los días no son canjeables por dinero.',
        '!LA COMPENSACIÓN DE ESTA SECCIÓN ES EL ÚNICO REMEDIO DE EL CLIENTE POR LA INDISPONIBILIDAD DE LA PLATAFORMA, SIN PERJUICIO DE LA RESPONSABILIDAD POR DOLO O CULPA GRAVE PREVISTA EN LA SECCIÓN 5.',
      ],
    },
    {
      title: '7. Soporte',
      blocks: [
        `Canales: WhatsApp ${T.whatsapp} y correo ${T.correo}. Horario: ${T.horarioSoporte}.`,
        'Tiempos objetivo de primera respuesta, contados dentro del horario de soporte:',
        [
          'Crítico (no se puede vender ni cobrar en ninguna caja por una falla de LA PLATAFORMA): dos (2) horas.',
          'Normal (una función no sirve, pero se puede seguir vendiendo): un (1) día hábil.',
          'Consultas de uso y sugerencias: tres (3) días hábiles.',
        ],
        'Son metas de atención, no plazos de solución garantizados: cada caso se resuelve en el menor tiempo razonable según su complejidad.',
        'Incluye: resolver dudas de uso, atender fallas del software y orientar la configuración básica. No incluye, y se cotiza aparte: capacitaciones personalizadas, carga o depuración de inventario, configuración de equipos, impresoras o redes del negocio, ni desarrollos a la medida.',
      ],
    },
    {
      title: '8. Copias de seguridad',
      blocks: [
        'LA PLATAFORMA hace cada noche una copia de seguridad automática de la información de cada negocio y conserva las siete (7) más recientes. El dueño de la cuenta puede hacer copias manuales (se conservan las veinte (20) más recientes) y descargar toda su información en cualquier momento.',
        'Restaurar una copia reemplaza la información actual por la de la copia: lo registrado después de esa copia puede perderse. Por eso, en el peor caso, la pérdida puede ser de hasta un (1) día de operaciones.',
        'Las copias se guardan en la infraestructura contratada por EL TITULAR y sirven para la continuidad del servicio. No reemplazan las copias que EL CLIENTE debe descargar periódicamente (ver Términos, sección 6).',
      ],
    },
    {
      title: '9. Reclamos',
      blocks: [
        `Cualquier reclamo debe presentarse por escrito a ${T.correo} dentro de los treinta (30) días calendario siguientes al hecho, describiendo lo ocurrido y adjuntando las pruebas disponibles. EL TITULAR responderá dentro de los quince (15) días hábiles siguientes.`,
      ],
    },
  ],
}

// ============================================================================================ FACTURACIÓN

const FACTURACION: LegalDoc = {
  key: 'FACTURACION',
  slug: 'facturacion-electronica',
  title: 'Anexo de Facturación Electrónica',
  short: 'Anexo de facturación electrónica',
  summary: [
    'Ecomerxio POS se conecta con un proveedor tecnológico autorizado por la DIAN para enviar tus facturas. Ecomerxio no es proveedor tecnológico autorizado.',
    'Tú sigues siendo el obligado a facturar: respondes por expedir y entregar tus facturas y por que los datos sean correctos.',
    'Si la DIAN o el proveedor rechazan un documento, lo ves en el sistema y lo corriges. Una factura aceptada no se edita: se corrige con nota crédito.',
    'Los documentos se pagan por paquetes o según tu plan; no son ilimitados salvo que el plan lo diga.',
  ],
  sections: [
    {
      title: '1. Alcance',
      blocks: [
        'Este anexo hace parte de los Términos y aplica cuando EL CLIENTE activa la facturación electrónica en LA PLATAFORMA.',
        'El módulo permite generar y enviar los documentos electrónicos que LA PLATAFORMA tenga disponibles (factura electrónica de venta, documento equivalente electrónico POS y notas crédito) por medio de un proveedor tecnológico autorizado por la DIAN que EL TITULAR integra. El nombre del proveedor se informa en la sección de Facturación de LA PLATAFORMA antes de activar el módulo.',
        '!EL TITULAR DESARROLLA E INTEGRA EL SOFTWARE; NO ES PROVEEDOR TECNOLÓGICO AUTORIZADO POR LA DIAN NI PRESTA LOS SERVICIOS PROPIOS DE ESE PROVEEDOR.',
      ],
    },
    {
      title: '2. Lo que debe tener EL CLIENTE',
      blocks: [
        [
          'Estar inscrito como facturador electrónico ante la DIAN y haber autorizado en el portal de la DIAN al proveedor tecnológico que usa LA PLATAFORMA.',
          'Tener una resolución de numeración vigente y registrarla correctamente en LA PLATAFORMA.',
          'Mantener completos y actualizados sus datos fiscales, la clasificación tributaria de sus productos y los datos de los clientes a quienes factura.',
          'Cumplir los demás requisitos que la DIAN o el proveedor exijan, y aceptar las condiciones del proveedor cuando se le pidan.',
        ],
      ],
    },
    {
      title: '3. Responsabilidades',
      blocks: [
        'EL CLIENTE es el obligado a facturar. Le corresponde expedir y entregar sus facturas, la veracidad de su contenido (precios, impuestos, datos del comprador), el uso correcto de la numeración y el cumplimiento de sus obligaciones tributarias, aunque intervenga una solución tecnológica.',
        'EL TITULAR responde por que LA PLATAFORMA envíe al proveedor la información tal como EL CLIENTE la registró y por informar el estado que el proveedor devuelva. El proveedor responde por la validación, transmisión y demás servicios que presta, según sus propias condiciones.',
        'La prefactura y las validaciones de LA PLATAFORMA ayudan a detectar errores, pero no sustituyen la revisión de EL CLIENTE ni garantizan que la DIAN acepte un documento.',
      ],
    },
    {
      title: '4. Rechazos y correcciones',
      blocks: [
        'Cada documento muestra su estado (pendiente, aceptado, rechazado, sin respuesta). Los rechazados aparecen en el centro de errores con la causa informada por el proveedor, para que EL CLIENTE corrija y vuelva a enviar.',
        'Un documento aceptado no se modifica ni se borra: se corrige o anula con los mecanismos que permite la norma, como la nota crédito.',
      ],
    },
    {
      title: '5. Contingencias',
      blocks: [
        'Si la DIAN, el proveedor o la conexión fallan, LA PLATAFORMA deja los documentos en cola y los reintenta, consultando antes de reenviar para no duplicarlos.',
        'Esto no reemplaza los procedimientos de contingencia que establezca la DIAN: EL CLIENTE debe conocerlos y aplicarlos cuando corresponda. La venta sin internet de LA PLATAFORMA tampoco los reemplaza.',
      ],
    },
    {
      title: '6. Documentos, paquetes y costos',
      blocks: [
        'El plan contratado indica cuántos documentos incluye o si se compran por paquetes. Los documentos no son ilimitados, salvo que el plan lo diga expresamente.',
        'Cada documento enviado al proveedor consume una unidad, también cuando resulta rechazado si el proveedor lo cobra. Las unidades no usadas vencen según las condiciones del plan o del paquete.',
        'Si el proveedor cambia sus costos, EL TITULAR podrá ajustar el precio de los documentos avisando con treinta (30) días de anticipación.',
      ],
    },
    {
      title: '7. Cambios normativos y conservación',
      blocks: [
        'Cuando la DIAN cambie sus requisitos técnicos, EL TITULAR actualizará LA PLATAFORMA en un tiempo razonable; mientras tanto, algunas funciones podrán limitarse.',
        'EL CLIENTE debe conservar sus facturas y soportes por el tiempo que exija la ley. LA PLATAFORMA los mantiene disponibles mientras la cuenta esté activa y permite descargarlos; al terminar el contrato aplica el plazo de la sección 11 de los Términos.',
      ],
    },
  ],
}

// ============================================================================================ ENCARGO

const ENCARGO: LegalDoc = {
  key: 'ENCARGO',
  slug: 'encargo-de-datos',
  title: 'Acuerdo de Encargo del Tratamiento de Datos Personales',
  short: 'Encargo del tratamiento de datos de tus clientes',
  summary: [
    'Los datos de tus clientes, proveedores y empleados que registras en el sistema son tu responsabilidad: tú eres el "Responsable".',
    'Ecomerxio solo los guarda y procesa por tu cuenta para prestarte el servicio: es el "Encargado".',
    'Tú debes pedirles autorización a tus clientes para tener sus datos (abajo hay un texto modelo).',
  ],
  sections: [
    {
      title: '1. Objeto y roles',
      blocks: [
        'Al usar LA PLATAFORMA, EL CLIENTE registra datos personales de terceros: sus clientes (nombre, documento, teléfono, correo, dirección, compras, saldos de fiado), sus proveedores y sus empleados (los "Datos del Cliente").',
        `Frente a los Datos del Cliente, EL CLIENTE es el RESPONSABLE del tratamiento y ${T.nombre} (${T.marca}) es el ENCARGADO, en los términos del artículo 3 de la Ley 1581 de 2012. Este acuerdo es el contrato de transmisión de datos previsto en el artículo 2.2.2.25.5.2 del Decreto 1074 de 2015.`,
      ],
    },
    {
      title: '2. Obligaciones de EL CLIENTE (Responsable)',
      blocks: [
        [
          'Contar con la autorización previa, expresa e informada de los titulares cuando la ley la exija, y conservar prueba de ella.',
          'Informar a los titulares para qué usa sus datos y cuáles son sus derechos, y tener su propia política de tratamiento.',
          'Registrar solo los datos necesarios y no registrar datos sensibles ni datos de niños, niñas o adolescentes, salvo que la ley lo permita y cuente con la autorización correspondiente.',
          'Atender las consultas y reclamos de los titulares (LA PLATAFORMA le permite consultar, corregir y exportar sus datos).',
          'Inscribir sus bases de datos en el Registro Nacional de Bases de Datos cuando esté obligado a hacerlo.',
        ],
        'Texto modelo de autorización que EL CLIENTE puede usar en su negocio: "Autorizo a [NOMBRE DEL NEGOCIO] a tratar mis datos personales para registrar mis compras, expedir mis facturas, gestionar créditos, enviarme comprobantes y comunicaciones de mis compras, conforme a su política de tratamiento de datos. Conozco mis derechos a conocer, actualizar, rectificar y suprimir mis datos y a revocar esta autorización escribiendo a [CORREO DEL NEGOCIO]."',
      ],
    },
    {
      title: '3. Obligaciones de EL TITULAR (Encargado)',
      blocks: [
        'Conforme al artículo 18 de la Ley 1581 de 2012, EL TITULAR se obliga a:',
        [
          'Tratar los Datos del Cliente solo para prestar LA PLATAFORMA y siguiendo las instrucciones de EL CLIENTE, que se expresan en el uso que este hace de sus funciones.',
          'No venderlos, no cederlos y no usarlos para fines propios, salvo datos anonimizados y agregados que no identifiquen a nadie.',
          'Aplicar medidas razonables de seguridad: acceso por contraseña y roles, conexión cifrada (HTTPS), separación de la información de cada negocio, registro de actividad y copias de seguridad.',
          'Guardar confidencialidad, también por parte de sus colaboradores y proveedores.',
          'Avisar a EL CLIENTE, sin demora injustificada, de cualquier incidente de seguridad que afecte sus datos, para que este pueda informar a la Superintendencia de Industria y Comercio y a los titulares cuando corresponda.',
          'Apoyar a EL CLIENTE, dentro de las funciones de LA PLATAFORMA, en la atención de consultas y reclamos de los titulares.',
          'Al terminar el contrato, devolver los datos mediante la exportación y suprimirlos en el plazo de la sección 11 de los Términos.',
        ],
      ],
    },
    {
      title: '4. Subencargados y transferencia internacional',
      blocks: [
        'EL CLIENTE autoriza a EL TITULAR a apoyarse en proveedores que actúan como subencargados (alojamiento en la nube, base de datos, correo, mensajería, facturación electrónica, pasarelas de pago y, si EL CLIENTE lo activa, inteligencia artificial). Algunos tienen servidores fuera de Colombia, por ejemplo en los Estados Unidos. EL TITULAR elegirá proveedores que ofrezcan niveles adecuados de seguridad y protección de datos.',
        'La lista actualizada de subencargados se puede pedir en cualquier momento al correo de contacto.',
      ],
    },
    {
      title: '5. Responsabilidad',
      blocks: [
        'Cada parte responde por el cumplimiento de sus propias obligaciones. EL CLIENTE mantendrá indemne a EL TITULAR frente a reclamos, quejas y sanciones derivados de no contar con las autorizaciones de los titulares o de registrar datos de forma indebida. La responsabilidad de EL TITULAR se sujeta a la Política de Servicio y Limitación de Responsabilidad.',
      ],
    },
  ],
}

// ============================================================================================ PRIVACIDAD

const PRIVACIDAD: LegalDoc = {
  key: 'PRIVACIDAD',
  slug: 'privacidad',
  title: 'Política de Tratamiento de Datos Personales',
  short: 'Política de privacidad y tratamiento de datos',
  summary: [
    'Usamos tus datos (nombre, correo, celular, datos del negocio) para darte el servicio, cobrarte, darte soporte y avisarte cosas importantes.',
    'No vendemos tus datos. Algunos proveedores (servidores en la nube, correo, pagos) los procesan por nosotros, a veces fuera de Colombia.',
    `Puedes pedir conocer, corregir o borrar tus datos escribiendo a ${T.correoDatos}.`,
  ],
  sections: [
    {
      title: '1. Responsable',
      blocks: [
        `El responsable del tratamiento de los datos personales de los usuarios de LA PLATAFORMA es ${ID}. Dirección: ${T.direccion}. Correo: ${T.correoDatos}. WhatsApp: ${T.whatsapp}.`,
        'Esta política cumple la Ley 1581 de 2012, el Decreto 1074 de 2015 (Capítulo 25) y demás normas que los modifiquen. Los datos que los negocios registran sobre sus propios clientes se rigen además por el Acuerdo de Encargo del Tratamiento de Datos.',
      ],
    },
    {
      title: '2. Qué datos tratamos',
      blocks: [
        [
          'Del dueño y los usuarios: nombre, correo, celular, contraseña (guardada cifrada, nunca en texto), PIN de autorización (cifrado), rol y permisos.',
          'Del negocio: nombre comercial, razón social, NIT o cédula, dirección, ciudad, teléfono, logo, datos fiscales y datos de facturación.',
          'De uso y seguridad: fecha y hora de ingreso, dirección IP, navegador y dispositivo, acciones realizadas (registro de actividad) y prueba de aceptación de estos documentos.',
          'De pago: plan, fechas y valores pagados y referencias de la transacción. Los datos de tarjetas los procesa la pasarela de pagos; EL TITULAR no los almacena.',
          'Lo que el usuario escriba al soporte o al asistente con inteligencia artificial.',
        ],
        'LA PLATAFORMA no pide datos sensibles (salud, origen racial, orientación política o religiosa, biométricos). La venta sin internet guarda copias de la información en el navegador del equipo (almacenamiento local); por eso cada negocio debe cuidar el acceso a sus equipos.',
      ],
    },
    {
      title: '3. Para qué los usamos (finalidades)',
      blocks: [
        [
          'Crear y administrar la cuenta, autenticar a los usuarios y prestar las funciones de LA PLATAFORMA.',
          'Cobrar la suscripción, expedir los documentos de cobro y llevar la contabilidad de EL TITULAR.',
          'Dar soporte, atender peticiones, quejas y reclamos, y enviar avisos del servicio (cambios, mantenimientos, seguridad, vencimientos).',
          'Proteger la seguridad de LA PLATAFORMA, prevenir fraudes y conservar prueba de las aceptaciones y operaciones.',
          'Mejorar el servicio con estadísticas anónimas y agregadas.',
          'Enviar información comercial, novedades y encuestas de Ecomerxio. Esta finalidad es opcional: se puede pedir dejar de recibirla en cualquier momento sin afectar el servicio.',
          'Cumplir obligaciones legales y requerimientos de autoridades competentes.',
        ],
      ],
    },
    {
      title: '4. Con quién los compartimos',
      blocks: [
        'EL TITULAR no vende ni alquila datos personales. Puede transmitirlos a proveedores que le ayudan a prestar el servicio (encargados), con quienes exige confidencialidad y seguridad: alojamiento y base de datos en la nube, envío de correos y mensajes, pasarelas de pago, proveedores tecnológicos de facturación electrónica y, si el negocio lo activa, proveedores de inteligencia artificial.',
        'Algunos de esos proveedores tienen servidores fuera de Colombia (por ejemplo, en los Estados Unidos). Al autorizar esta política, el titular autoriza de forma expresa esa transmisión y transferencia internacional, en los términos del artículo 26 de la Ley 1581 de 2012.',
        'También podrán entregarse a autoridades que los pidan en ejercicio de sus funciones legales.',
      ],
    },
    {
      title: '5. Derechos del titular',
      blocks: [
        'Como titular de los datos, usted tiene derecho a:',
        [
          'Conocer, actualizar y rectificar sus datos.',
          'Pedir prueba de la autorización que otorgó.',
          'Ser informado del uso que se les ha dado.',
          'Presentar quejas ante la Superintendencia de Industria y Comercio, después de haber agotado el trámite de consulta o reclamo ante EL TITULAR.',
          'Revocar la autorización y pedir la supresión de los datos, cuando no exista un deber legal o contractual de conservarlos.',
          'Acceder gratuitamente a sus datos.',
        ],
      ],
    },
    {
      title: '6. Cómo ejercerlos',
      blocks: [
        `Escriba a ${T.correoDatos} indicando su nombre, documento, la petición y un dato de contacto. Muchas acciones pueden hacerse directamente en LA PLATAFORMA (Mi cuenta, Usuarios, Copias de seguridad → Exportar).`,
        [
          'Consultas: se responden en máximo diez (10) días hábiles, prorrogables por cinco (5) días hábiles más, avisando el motivo.',
          'Reclamos (corrección, actualización, supresión o revocación): se responden en máximo quince (15) días hábiles, prorrogables por ocho (8) días hábiles más, avisando el motivo. Si el reclamo está incompleto, se pedirá completarlo dentro de los cinco (5) días siguientes; si pasan dos (2) meses sin hacerlo, se entenderá desistido.',
        ],
      ],
    },
    {
      title: '7. Seguridad y conservación',
      blocks: [
        'EL TITULAR aplica medidas técnicas, humanas y administrativas razonables: conexión cifrada, contraseñas cifradas, bloqueo por intentos fallidos, permisos por rol, separación de la información de cada negocio, registro de actividad y copias de seguridad. Ningún sistema es infalible; ante un incidente se actuará conforme a la ley.',
        'Los datos se conservan mientras la cuenta esté activa y, después, por el tiempo necesario para cumplir obligaciones legales, contables y de prueba, o para atender reclamos.',
      ],
    },
    {
      title: '8. Menores de edad',
      blocks: ['LA PLATAFORMA está dirigida a negocios. No está destinada a menores de edad y no se recogen sus datos de forma intencional.'],
    },
    {
      title: '9. Vigencia y cambios',
      blocks: [
        `Esta política rige desde el ${LEGAL_DATE}. Los cambios importantes se informarán por correo o dentro de LA PLATAFORMA antes de aplicarse y, cuando se requiera, se pedirá una nueva autorización.`,
      ],
    },
  ],
}

export const LEGAL_DOCS: LegalDoc[] = [TERMINOS, LICENCIA, SERVICIO, FACTURACION, ENCARGO, PRIVACIDAD]

export function legalDoc(keyOrSlug: string): LegalDoc | undefined {
  return LEGAL_DOCS.find((d) => d.key === keyOrSlug || d.slug === keyOrSlug)
}

/** Lo que acepta el dueño con la casilla de términos (todo menos la autorización de datos, que va aparte). */
export const CONTRACT_DOCS: LegalKey[] = ['TERMINOS', 'LICENCIA', 'SERVICIO', 'FACTURACION', 'ENCARGO']
