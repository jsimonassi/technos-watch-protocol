package br.com.grupotechnos.watch.protocol.driver

/**
 * Lugar reservado para drivers implementados pelo host sobre a SDK de um fabricante.
 *
 * Nesta PoC não há implementação. A ideia: famílias que ainda dependem da SDK do fabricante (ex.: iDO)
 * terão um adaptador escrito no host (Kotlin no Android, Swift no iOS) que implementa esta interface com
 * callbacks simples, porque Swift não implementa funções `suspend`. A sessão envolveria o adaptador num
 * driver interno e o resto do sistema não perceberia a diferença.
 *
 * Os métodos serão definidos quando o primeiro adaptador existir; declarar agora sem uso só criaria API para manter.
 */
public interface HostDriver
